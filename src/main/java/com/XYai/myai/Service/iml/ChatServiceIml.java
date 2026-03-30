package com.XYai.myai.Service.iml;

import com.XYai.myai.Annotation.RagTraceNode;
import com.XYai.myai.Annotation.rateLimit;
import com.XYai.myai.Chat.ChatMessage;
import com.XYai.myai.Memory.ConversationMemorySummaryService;
import com.XYai.myai.Memory.LoadSession;
import com.XYai.myai.intent.IntentResult;
import com.XYai.myai.rewrite.RewriteResult;
import com.XYai.myai.rewrite.QueryRewriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import com.XYai.myai.Service.ChatService;
import org.glassfish.jaxb.core.v2.TODO;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 聊天服务实现，基于 Ollama 模型并使用内存保存会话上下文。
 */
@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class ChatServiceIml implements ChatService {

    @Resource
    private final ChatModel chatModel;
    @Resource
    private QueryRewriter queryRewriter;
    @Resource
    private ConversationMemorySummaryService conversationMemorySummaryService;
    @Resource
    private IntentResult intentResult;

    public static final String SYSTEM_MESSAGE = """
            你是活泼的AI,名字叫做XY,专为用户解答不知道的知识
            与用户积极沟通,在没有准确答案时候输出(我暂时还不知道这个知识)
            """;

    /**
     * 处理一轮对话，按会话 ID 维护上下文并返回模型回复。
     *
     * @param message 用户输入内容
     * @param conversationId 会话 ID，可为空
     * @return 模型回复文本
     */
    @RagTraceNode(name = "对话", type = "chat")
    @rateLimit(limit = 3,rateName = "chat")
    public Flux<String> DoChat(String message, String conversationId) {
        // 基础参数校验，避免空请求进入模型。
        if (message == null || message.isBlank()) {
            return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有输入信息"));
        }
        // RAG 对话
        //并行加载摘要和历史记录
        //LoadSession load = conversationMemorySummaryService.load(conversationId);
        //问题重写
        RewriteResult rewrittenMessage = queryRewriter.rewrite(message);
        //TODO 意图识别用户消息
        intentResult.recognize(rewrittenMessage);
        //TODO 网页 | 向量检索
    
        //TODO 重排序
    
        //LLM 生成
        //对话
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_MESSAGE),
                new UserMessage(rewrittenMessage.getRewrittenQuery())
        ));
        StringBuilder fullAnswer = new StringBuilder();
        return chatModel.stream(prompt)
                .map(this::extractChunkText)
                .filter(chunk -> chunk != null && !chunk.isBlank())
                .doOnNext(fullAnswer::append)
                .doOnComplete(() -> {
                    ChatMessage chatMessage = ChatMessage.builder()
                            .userMessage(rewrittenMessage.getRewrittenQuery())
                            .assistantMessage(fullAnswer.toString())
                            .build();
                    conversationMemorySummaryService.compressIfNeeded(conversationId, chatMessage);
                });
    }

    /**
     * 从流式响应块中提取文本内容。
     *
     * @param response 聊天模型的响应块
     * @return 提取出的文本内容，若为空则返回空字符串
     */
    private String extractChunkText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
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
