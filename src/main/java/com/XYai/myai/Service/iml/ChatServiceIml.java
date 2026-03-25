package com.XYai.myai.Service.iml;

import com.XYai.myai.Annotation.rateLimit;
import com.XYai.myai.core.memory.MemoryStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.ollama.OllamaChatModel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import com.XYai.myai.Service.ChatService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天服务实现，基于 Ollama 模型并使用内存保存会话上下文。
 */
@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class ChatServiceIml implements ChatService {

    @Resource
    private MemoryStore memoryStore;
    private final OllamaChatModel ollamaChatModel;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final String SYSTEM_MESSAGE = """
            你是活泼的AI,名字叫做XY,专为用户解答不知道的知识
            与用户积极沟通,在没有准确答案时候输出(我暂时还不知道这个知识)
            """;

    private static final String DEFAULT_CONVERSATION_ID = "default";


    /**
     * 处理一轮对话，按会话 ID 维护上下文并返回模型回复。
     *
     * @param message 用户输入内容
     * @param conversationId 会话 ID，可为空
     * @return 模型回复文本
     */
    @rateLimit(limit = 3,rateName = "chat", windowMs = 1000)
    public String DoChat(String message, String conversationId) {
        // 基础参数校验，避免空请求进入模型。
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有输入信息");
        }

        String normalizedConversationId = normalizeConversationId(conversationId);
        String summaryKey = "Chat:Mem:{cid}:recent" + normalizedConversationId + "Summary";
        String summary = stringRedisTemplate.opsForValue().get(summaryKey);
        List<String> context = memoryStore.getContext(normalizedConversationId);
        String contextLines = context.isEmpty() ? "(无)" : String.join("\n", context);
        String safeSummary = (summary == null || summary.isBlank()) ? "(无)" : summary;
        // 使用结构化消息组装上下文，避免把系统提示和用户问题混成一条文本。
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_MESSAGE));
        messages.add(SystemMessage.from("历史上下文:\n" + contextLines + "\n\n历史摘要:\n" + safeSummary));
        messages.add(UserMessage.from(message));
        //获取ai消息
        ChatResponse response = ollamaChatModel.chat(messages);
        String aiText = response.aiMessage().text();

        memoryStore.addInteraction(normalizedConversationId, message, aiText);
        return aiText;
    }

    /**
     * 规范化会话 ID，空值时回退到默认会话。
     *
     * @param conversationId 原始会话 ID
     * @return 规范化后的会话 ID
     */
    private String normalizeConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return DEFAULT_CONVERSATION_ID;
        }
        return conversationId;
    }

    /**
     * 启动后校验核心模型是否完成注入。
     */
    @jakarta.annotation.PostConstruct
    private void init() {
        if (this.ollamaChatModel == null) {
            log.error("OllamaChatModel was not injected into ChatServiceIml - application context may be misconfigured");
            throw new IllegalStateException("OllamaChatModel bean not injected into ChatServiceIml");
        }
        log.info("OllamaChatModel injected into ChatServiceIml: {}", this.ollamaChatModel.getClass().getName());
    }
}
