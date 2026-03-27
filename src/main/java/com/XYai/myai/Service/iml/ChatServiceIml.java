package com.XYai.myai.Service.iml;

import com.XYai.myai.Annotation.RagTraceNode;
import com.XYai.myai.Annotation.rateLimit;
import com.XYai.myai.core.dto.RewriteResult;
import com.XYai.myai.core.intent.IntentResult;
import com.XYai.myai.core.memory.MemoryStore;
import com.XYai.myai.core.rewrite.QueryRewriter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import com.XYai.myai.Service.ChatService;
import org.aspectj.weaver.ast.Var;
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

import com.XYai.myai.core.dto.ChatConversation;
import com.XYai.myai.core.dto.ChatMemorySummary;
import com.XYai.myai.core.dto.ChatSessionRecord;
import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.mapper.ChatMemorySummaryMapper;
import com.XYai.myai.mapper.ChatSessionRecordMapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.XYai.myai.Controller.UserController.USERID;

/**
 * 聊天服务实现，基于 Ollama 模型并使用内存保存会话上下文。
 */
@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class ChatServiceIml implements ChatService {

    @Resource
    private MemoryStore memoryStore;
    private final ChatModel chatModel;
    private final ChatSessionRecordMapper chatSessionRecordMapper;
    private final ChatConversationMapper chatConversationMapper;
    private final ChatMemorySummaryMapper chatMemorySummaryMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private QueryRewriter queryRewriter;
    @Resource
    private IntentResult intentResult;
    public static final String SYSTEM_MESSAGE = """
            你是活泼的AI,名字叫做XY,专为用户解答不知道的知识
            与用户积极沟通,在没有准确答案时候输出(我暂时还不知道这个知识)
            """;

    private static final String DEFAULT_CONVERSATION_ID = "default";
    private static final int MAX_INTERACTION_RECORDS = 20;

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
        StringBuilder fullAiText = new StringBuilder();
        String normalizedConversationId = normalizeConversationId(conversationId);
        // RAG 对话
        //并行加载摘要和历史记录
        String summyAndHistory = memoryStore.load(normalizedConversationId);
        //问题重写
        RewriteResult rewriteResult = queryRewriter.rewrite(message,summyAndHistory);
        log.info(String.valueOf(rewriteResult));
        //意图识别用户消息
        intentResult.recognize(rewriteResult);
        //TODO 网页 | 向量检索
    
        //TODO 重排序
    
        //LLM 生成
        //对话
        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_MESSAGE),
                new UserMessage(message)
        ));
        return null;
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
     * 异步持久化本次交互记录到存储中。
     *
     * @param normalizedConversationId 规范化后的会话ID
     * @param message 用户消息
     * @param fullAiText AI生成的完整回复内容
     */
    private void persistInteraction(String normalizedConversationId, String message, StringBuilder fullAiText) {
        if (fullAiText.isEmpty()) {
            return;
        }
        String assistantReply = fullAiText.toString();
        // 异步保存记录与压缩，防止阻塞响应流或者出现跨线程阻塞异常
        CompletableFuture.runAsync(() -> {
            try {
                upsertConversation(normalizedConversationId, message);
                ChatSessionRecord record = new ChatSessionRecord();
                record.setConversationId(normalizedConversationId);
                record.setUserMessage(message);
                record.setAssistantMessage(assistantReply);
                record.setCreatedAt(LocalDateTime.now().withNano(0));
                chatSessionRecordMapper.insert(record);
                // 每个会话最多保留最近记录，超出自动删除最旧记录
                long total = chatSessionRecordMapper.countByConversationId(normalizedConversationId);
                int deleteCount = (int) Math.max(0, total - MAX_INTERACTION_RECORDS);
                if (deleteCount > 0) {
                    chatSessionRecordMapper.deleteOldestByLimit(normalizedConversationId, deleteCount);
                }
            } catch (Exception e) {
                log.error("保存会话到数据库失败", e);
            }
            //
            try {
                memoryStore.addInteraction(normalizedConversationId, message, assistantReply);
                String summaryKey = "Chat:Mem:{cid}:recent" + normalizedConversationId + "Summary";
                String summary = stringRedisTemplate.opsForValue().get(summaryKey);
                if (summary != null && !summary.isBlank()) {
                    upsertSummary(normalizedConversationId, summary);
                }
            } catch (Exception e) {
                log.error("保存对话记忆失败", e);
            }
        });
    }

    /**
     * 设置会话
     * @param conversationId 会话ID
     * @param message 用户消息
     */
    private void upsertConversation(String conversationId, String message) {
        ChatConversation existing = chatConversationMapper.selectOne(
                new LambdaQueryWrapper<ChatConversation>()
                        .eq(ChatConversation::getConversationId, conversationId)
                        .last("LIMIT 1")
        );
        if (existing != null) {
            return;
        }
        ChatConversation conversation = new ChatConversation();
        conversation.setConversationId(conversationId);
        if (USERID != null && USERID > 0) {
            conversation.setUserId(String.valueOf(USERID));
        }
        conversation.setTitle(buildConversationTitle(message));
        conversation.setCreatedAt(LocalDateTime.now().withNano(0));
        chatConversationMapper.insert(conversation);
    }

    /**
     * 设置会话标题
     * @param message 用户最后会话当标题
     * @return 返回标题
     */
    private String buildConversationTitle(String message) {
        if (message == null || message.isBlank()) {
            return "新会话";
        }
        String trimmed = message.trim();
        return trimmed.length() > 20 ? trimmed.substring(0, 20) : trimmed;
    }

    /**
     * 更新会话摘要
     * @param conversationId 会话ID
     * @param summaryText 摘要文本
     */
    private void upsertSummary(String conversationId, String summaryText) {
        ChatMemorySummary existing = chatMemorySummaryMapper.selectOne(
                new LambdaQueryWrapper<ChatMemorySummary>()
                        .eq(ChatMemorySummary::getConversationId, conversationId)
                        .last("LIMIT 1")
        );
        if (existing == null) {
            ChatMemorySummary summary = new ChatMemorySummary();
            summary.setConversationId(conversationId);
            summary.setSummaryText(summaryText);
            chatMemorySummaryMapper.insert(summary);
            return;
        }
        chatMemorySummaryMapper.update(
                null,
                new LambdaUpdateWrapper<ChatMemorySummary>()
                        .eq(ChatMemorySummary::getConversationId, conversationId)
                        .set(ChatMemorySummary::getSummaryText, summaryText)
        );
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
        if (this.chatModel == null) {
            log.error("ChatModel was not injected into ChatServiceIml - application context may be misconfigured");
            throw new IllegalStateException("ChatModel bean not injected into ChatServiceIml");
        }
        log.info("ChatModel injected into ChatServiceIml: {}", this.chatModel.getClass().getName());
    }
}
