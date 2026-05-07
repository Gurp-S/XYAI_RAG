package com.XYai.myai.rag.chat.Service.Impl;

import com.XYai.myai.rag.aop.Annotation.RagTraceNode;
import com.XYai.myai.rag.channel.MultiChannelRetrievalEngine;
import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.chat.POJO.ChatMessage;
import com.XYai.myai.rag.chat.Service.ChatService;
import com.XYai.myai.rag.intent.IntentResult;
import com.XYai.myai.rag.intent.POJO.SubQuestionIntent;
import com.XYai.myai.rag.mcp.POJO.ToolProcessorResult;
import com.XYai.myai.rag.mcp.ToolDecisionManager;
import com.XYai.myai.rag.memory.ConversationMemorySummaryService;
import com.XYai.myai.rag.memory.POJO.LoadSession;
import com.XYai.myai.rag.rewrite.POJO.RewriteResult;
import com.XYai.myai.rag.rewrite.QueryRewriter;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.POJO.User;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * 聊天服务实现，基于 Ollama 模型并使用内存保存会话上下文。
 * 优化点：
 * 1. 前置处理（重写/意图/召回/MCP等待）异步化，避免阻塞 Netty 线程。
 * 2. 会话摘要持久化异步执行，不阻塞响应流。
 * 3. 日志分级，减少序列化开销。
 * 4. 字符串模板预定义，减少运行时拼接。
 */
@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    private static final String SYSTEM_MESSAGE = """
            你叫XY一个专业活泼可爱的AI
            1. 回答要求：准确、逻辑清晰、简洁避免废话和格式化
            2. 风格：闲聊时亲和有趣，解答问题时严谨专业
            3. 工具：基于工具结果回答，无关结果时说明并给出常识性答案
            4. 参考资料：作为辅助，无关忽略，仅精炼引用关键信息
            5. 语言：通俗易懂、自然流畅，拒绝生硬回答
            """;

    private static final String USER_MESSAGE_TEMPLATE = """
            参考文档:<<%s>>
            工具调用结果:<<%s>>
            更早的历史对话摘要:<<%s>>
            历史对话:<<%s>>
            用户消息:<<%s>>
            """;

    @Resource
    private ChatModel chatModel;
    @Resource
    private QueryRewriter queryRewriter;
    @Resource
    private ConversationMemorySummaryService conversationMemorySummaryService;
    @Resource
    private IntentResult intentResult;
    @Resource
    private MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    @Resource
    private ToolDecisionManager toolDecisionManager;
    @Resource
    private ChatClient chatClient;
    @Resource(name = "memeryExecutor")
    private ThreadPoolTaskExecutor memoryCompactExecutor;
    @Resource(name = "mcpExecutor")
    private ThreadPoolTaskExecutor mcpExecutor;
    @Resource(name = "chatExecutor")
    private ThreadPoolTaskExecutor chatExecutor;

    /**
     * 处理一轮对话，按会话 ID 维护上下文并返回模型回复。
     *
     * @param message        用户输入内容
     * @param conversationId 会话 ID，可为空
     * @return 模型回复文本
     */
    @RagTraceNode(name = "对话", type = "chat")
    public Flux<String> DoChat(String message, String conversationId) {
        if (message == null || message.isBlank()) {
            return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有输入信息"));
        }

        // 在主线程中获取用户信息和上下文
        User currentUser = LoginUserInfoManager.get();
        Long userId = Optional.ofNullable(currentUser).map(User::getId).orElse(null);
        SecurityContext securityCtx = SecurityContextHolder.getContext();

        // 异步任务：MCP 和 Memory 加载（使用各自线程池，也建议配置 TaskDecorator）
        CompletableFuture<List<ToolProcessorResult>> mcpFuture =
                CompletableFuture.supplyAsync(() -> toolDecisionManager.toolProcessor(message), mcpExecutor);
        CompletableFuture<LoadSession> memoryFuture =
                CompletableFuture.supplyAsync(() -> conversationMemorySummaryService.load(conversationId), memoryCompactExecutor);

        return Mono.fromCallable(() -> {
                    // 重写和意图识别（同步但快速）
                    RewriteResult rewritten = queryRewriter.rewrite(message, null);
                    List<SubQuestionIntent> intents = intentResult.recognize(rewritten, null);
                    List<RetrievedChunk> retrieved = multiChannelRetrievalEngine.retrieve(
                            intents, rewritten, conversationId, message);
                    return new Object[]{rewritten, intents, retrieved};
                })
                .subscribeOn(Schedulers.fromExecutor(chatExecutor)) // 使用 chatExecutor
                .flatMap(arr -> {
                    RewriteResult rewritten = (RewriteResult) arr[0];
                    List<SubQuestionIntent> intents = (List<SubQuestionIntent>) arr[1];
                    List<RetrievedChunk> retrieved = (List<RetrievedChunk>) arr[2];

                    // 等待 MCP 和 Memory 结果
                    return Mono.zip(
                                    Mono.fromFuture(mcpFuture),
                                    Mono.fromFuture(memoryFuture)
                            )
                            .map(tuple -> {
                                List<ToolProcessorResult> toolResults = tuple.getT1();
                                LoadSession load = tuple.getT2();

                                String historyText = (load == null) ? "无" : load.getHistoryAsText();
                                String retrieveText = retrieved.isEmpty() ? "无" :
                                        retrieved.stream().map(RetrievedChunk::getContent)
                                        .filter(Objects::nonNull)
                                        .collect(Collectors.joining("\n---\n"));
                                String mcpText = toolResults.isEmpty() ? "无" :
                                        toolResults.stream().filter(ToolProcessorResult::isSuccess)
                                        .map(t -> t.getToolName() + ":\n" + t.getResult())
                                        .collect(Collectors.joining("\n----------------\n"));

                                String userMessage = String.format(USER_MESSAGE_TEMPLATE,
                                        retrieveText, mcpText,
                                        load == null ? "无" : load.getSummary(),
                                        historyText, message);
                                return new PromptData(SYSTEM_MESSAGE, userMessage, conversationId, userId);
                            });
                })
                .flatMapMany(promptData -> {
                    StringBuilder fullAnswer = new StringBuilder();
                    return chatClient.prompt()
                            .system(s -> s.text(promptData.systemMessage))
                            .user(u -> u.text(promptData.userMessage))
                            .stream()
                            .content()
                            .filter(s -> s != null && !s.isBlank())
                            .timeout(Duration.ofSeconds(90))
                            .doOnNext(fullAnswer::append)
                            .doOnComplete(() -> {
                                ChatMessage chatMsg = ChatMessage.builder()
                                        .userMessage(message)
                                        .assistantMessage(fullAnswer.toString())
                                        .userId(promptData.userId)
                                        .build();
                                CompletableFuture.runAsync(() -> {
                                    try {
                                        conversationMemorySummaryService.compressIfNeeded(promptData.conversationId, chatMsg);
                                    } catch (Exception e) {
                                        log.error("Failed to compress memory", e);
                                    }
                                }, memoryCompactExecutor);
                            })
                            .doFinally(signal -> SecurityContextHolder.setContext(securityCtx));
                });
    }

    /**
     * 启动后校验核心模型是否完成注入。
     */
    @jakarta.annotation.PostConstruct
    private void init() {
        if (this.chatModel == null) {
            log.error("ChatModel was not injected into ChatServiceImpl - application context may be misconfigured");
            throw new IllegalStateException("ChatModel bean not injected into ChatServiceImpl");
        }
        log.info("ChatModel injected into ChatServiceImpl: {}", this.chatModel.getClass().getName());
    }

    /**
     * 内部辅助类，封装 prompt 构建所需数据。
     */
    private record PromptData(String systemMessage, String userMessage, String conversationId, Long userId) {
    }
}