package com.XYai.myai.rag.chat.Service.Impl;

import com.XYai.myai.rag.aop.Annotation.RagTraceNode;
import com.XYai.myai.rag.channel.MultiChannelRetrievalEngine;
import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.chat.POJO.ChatMessage;
import com.XYai.myai.rag.chat.Service.ChatService;
import com.XYai.myai.rag.intent.IntentResult;
import com.XYai.myai.rag.intent.POJO.SubQuestionIntent;
import com.XYai.myai.rag.memory.ConversationMemorySummaryService;
import com.XYai.myai.rag.memory.POJO.LoadSession;
import com.XYai.myai.rag.rewrite.POJO.RewriteResult;
import com.XYai.myai.rag.rewrite.QueryRewriter;
import com.XYai.myai.user.LoginUserInfoManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 聊天服务实现，基于 Ollama 模型并使用内存保存会话上下文。
 */
@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

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
    private ToolCallbackProvider allToolsProvider;
    @Resource
    private ChatClient chatClient;


    /**
     * 处理一轮对话，按会话 ID 维护上下文并返回模型回复。
     *
     * @param message        用户输入内容
     * @param conversationId 会话 ID，可为空
     * @return 模型回复文本
     */
    @RagTraceNode(name = "对话", type = "chat")
    public Flux<String> DoChat(String message, String conversationId) {
        // 基础参数校验，避免空请求进入模型。
        if (message == null || message.isBlank()) {
            return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有输入信息"));
        }
        // RAG 对话
        // 并行加载摘要和历史记录
        LoadSession load = conversationMemorySummaryService.load(conversationId);
        // 问题重写
        RewriteResult rewrittenMessage = queryRewriter.rewrite(message, load);
        log.info("rewrittenMessage:{}", rewrittenMessage);
        // 意图识别用户消息
        List<SubQuestionIntent> questionIntents = intentResult.recognize(rewrittenMessage, load);
        log.info("questionIntents:{}", questionIntents);
        // TODO 网页检索

        // 多通道召回
        List<RetrievedChunk> retrieve = multiChannelRetrievalEngine.retrieve(questionIntents, rewrittenMessage, conversationId,message);
        log.info("retrieve:{}",retrieve.stream().map(RetrievedChunk::getMetadata).toList());
        // prompt生成（构建为 system 和 user 文本以便通过 ChatClient 的 fluent API 使用）
        String historyText = (load == null) ? "无" : load.getHistoryAsText();
        String retrieveText = (retrieve.isEmpty()) ? "无" :
                retrieve.stream()
                        .map(RetrievedChunk::getContent)
                        .filter(Objects::nonNull)
                        .collect(Collectors.joining("\n---\n"));
        String systemMessage = """
                你是活泼温柔、表达自然且业务专业的AI助手XY。
                1. 回答要求：答案精准唯一,逻辑通顺,拒绝无效废话与生硬格式化;
                2. 交互风格：用户闲聊时轻松活泼,语气亲和,适度趣味互动;解答问题时严谨专业;
                3. 工具能力：主动识别场景,优先调用MCP工具,支持多个工具组合混用,串联查询,借助工具数据完善回答,不凭空编造信息,工具不可用先进行常识回答但是要说明是常识回答;
                4. 文档规则：参考文档仅作辅助参考,无关联则完全忽略;禁止大段复制,回显原始JSON文档,只精简引用必要内容;
                5. 内容限制：语言通俗易懂,拒绝机械话术,贴合日常对话感。
                """;
        String userMessage = """
                参考文档:<<%s>>
                更早的历史对话摘要:<<%s>>
                历史对话:<<%s>>
                用户消息:<<%s>>
                """.formatted(retrieveText,load == null ? "无" : load.getSummary(), historyText, message);
        // 对话
        StringBuilder fullAnswer = new StringBuilder();
        Long userId;
        var user = LoginUserInfoManager.get();
        if (user != null) {
            userId = user.getId();
        } else {
            userId = null;
        }
        // 使用 ChatClient 的 fluent prompt builder 发起流式对话（stream().content() -> Flux<String>）
        // 将上游流发布为一个共享（multicast）流，防止框架或监控等对返回的 Flux 进行多次订阅
        // 导致重复调用模型/重复持久化的问题。
        SecurityContext context = SecurityContextHolder.getContext();
        Flux<String> stream = chatClient.prompt()
                .system(s -> s.text(systemMessage))
                .user(u -> u.text(userMessage))
                .stream()
                .content()
                .filter(s -> s != null && !s.isBlank())
                .timeout(Duration.ofSeconds(60))
                .doOnNext(fullAnswer::append)
                // 仅在流正常完成时持久化会话摘要，避免在超时/取消/错误场景下写入不完整内容
                .doOnComplete(() -> {
                    ChatMessage chatMessage = ChatMessage.builder()
                            .userMessage(message)
                            .assistantMessage(fullAnswer.toString())
                            .userId(userId)
                            .build();
                    conversationMemorySummaryService.compressIfNeeded(conversationId, chatMessage);
                })
                // 无论完成、错误还是取消，都要恢复安全上下文
                .doFinally(signal -> SecurityContextHolder.setContext(context));

        // 使用 publish().refCount(1) 或 share() 将上游连接并复用单个订阅，避免重复执行上游副作用。
        // publish().refCount(1) 会在第一个订阅时连接上游，并在最后一个取消后断开连接。
        return stream.publish().refCount(1);
    }

    /**
     * 启动后校验核心模型是否完成注入。
     */
    @jakarta.annotation.PostConstruct
    private void init() {
        if (this.chatModel == null) {
            log.error("ChatModel was not injected into ChatServiceIml - application context may be misconfigured");
            throw new IllegalStateException("ChatModel bean not injected into ChatServiceIml");
        }
        log.info("ChatModel injected into ChatServiceIml: {}", this.chatModel.getClass().getName());
    }
}
