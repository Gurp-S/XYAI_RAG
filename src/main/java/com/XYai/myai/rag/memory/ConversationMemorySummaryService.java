package com.XYai.myai.rag.memory;

import com.XYai.myai.commonUtils.redis.RedisKeyConfig;
import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.mapper.ChatSessionRecordMapper;
import com.XYai.myai.rag.aop.annotation.RagTraceContext;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.chat.ModelInvocationService;
import com.XYai.myai.rag.chat.pojo.ChatMessage;
import com.XYai.myai.rag.kafka.event.AnalyticsEvent;
import com.XYai.myai.rag.kafka.event.MemoryEvent;
import com.XYai.myai.rag.memory.pojo.ChatConversation;
import com.XYai.myai.rag.memory.pojo.ChatSessionRecord;
import com.XYai.myai.rag.memory.pojo.LoadSession;
import com.XYai.myai.rag.memory.pojo.MemoryProperties;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.xyAdmin.pojo.TokenUse;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 会话记忆与摘要服务（优化版）
 * <p>
 * 改进点：
 * 1. 摘要提示词结构化，提高摘要质量
 * 2. 线程池隔离：摘要生成使用独立线程池，避免阻塞快速任务
 * 3. 数据库清理改为清理旧对话消息，而非会话记录
 * 4. 历史加载只取最近 keepTurns 轮，避免全量拉取
 * 5. 标题截断按字符数处理，防止乱码
 */
@Slf4j
@Service
public class ConversationMemorySummaryService {

    private static final String systemMessage = """
            你是对话摘要助手。请基于「历史摘要」和「新对话」生成简洁摘要。
            规则：
            - 摘要长度不超过 %d 个字。
            - 只保留用户的重要信息、偏好、决定、事实或修正（以最新为准）。
            - 使用流畅、中立的第三人称描述（如"用户询问了...，助手回答了..."）。
            - 若历史摘要已有类似信息，不重复，仅补充新内容。
            - 若新对话无实质内容，保持摘要不变。
            - 输出仅摘要文本，无任何解释。
            """;
    private static final String userMessage = """
            历史摘要（不要复述）：
            %s
            
            新对话：
            %s
            
            输出更新后的摘要：
            """;


    private static final String SUMMARY_PLACEHOLDER = "无";
    private static final int MAX_TITLE_LENGTH = 20;          // 标题最大字符数
    private static final int MAX_DB_MESSAGES = 200;          // 每个会话保留的最大消息数

    @Resource(name = "memoryCache")
    private Cache<String, Object> localCache;

    @Resource
    private MemoryProperties memoryProperties;

    @Resource
    private ChatModel chatModel;

    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Resource
    @Lazy
    private ModelInvocationService modelInvocation;

    @Resource
    private ChatSessionRecordMapper chatSessionRecordMapper;

    @Resource
    private ChatConversationMapper chatConversationMapper;

    @Resource
    private RedissonClient redissonClient;

    /**
     * 异步触发摘要压缩（本地去重）
     */
    @RagTraceNode(name = "会话记忆压缩", type = "记忆压缩", taskIdArg = "root")
    public void compressIfNeeded(Long conversationId, ChatMessage message) {
        if (!memoryProperties.getSummaryEnabled()) {
            return;
        }

        Long userId = extractUserId(message);
        if (userId == null) {
            log.warn("compressIfNeeded: 无法获取用户ID, conversationId={}", conversationId);
            return;
        }

        kafkaTemplate.send("memory-cmd", String.valueOf(conversationId),
                new MemoryEvent(UUID.randomUUID().toString(), "COMPRESS",
                        conversationId, userId, JSON.toJSONString(message)));
    }

    /**
     * 加载会话上下文（仅加载最近 keepTurns 轮完整对话，避免全量拉取）
     */
    public LoadSession load(Long conversationId) {
        Long userId = LoginUserInfoManager.getUserId();
        if (userId == null) {
            return LoadSession.fromCollection(null, Collections.emptySet());
        }

        String conversationKey = RedisKeyConfig.userConversationRecord(userId, conversationId);
        String summaryKey = RedisKeyConfig.userSummaryRecord(userId, conversationId);

        int keepTurns = memoryProperties.getHistoryKeepTurns();

        // 先检查本地 Caffeine 缓存，没有的话再从 Redis 获取
        @SuppressWarnings("unchecked")
        Collection<String> convoObjs = (Collection<String>) localCache.get(conversationKey, k -> {
            RScoredSortedSet<String> scoredSet = redissonClient.getScoredSortedSet(k);
            return scoredSet.valueRange(-keepTurns, -1);
        });

        String summary = (String) localCache.get(summaryKey, k -> {
            RBucket<String> summaryBucket = redissonClient.getBucket(k);
            return summaryBucket.get();
        });

        Set<ChatMessage> conversations = parseConversations(convoObjs);
        return LoadSession.fromCollection(summary, conversations);
    }

    // ==================== 私有方法 ====================

    private Long extractUserId(ChatMessage message) {
        if (message != null && message.getUserId() != null) {
            return message.getUserId();
        }
        return LoginUserInfoManager.getUserId();
    }

    private Set<ChatMessage> parseConversations(Collection<String> convoObjs) {
        if (convoObjs == null || convoObjs.isEmpty()) {
            return Collections.emptySet();
        }

        Set<ChatMessage> conversations = new LinkedHashSet<>(convoObjs.size());
        for (String convoJson : convoObjs) {
            try {
                conversations.add(JSON.parseObject(convoJson, ChatMessage.class));
            } catch (Exception e) {
                log.warn("解析对话消息失败: {}", e.getMessage());
            }
        }
        return conversations;
    }

    /**
     * 核心压缩逻辑
     */
    public void doCompressIfNeeded(Long conversationId, ChatMessage message, Long userId) {
        int maxTurns = memoryProperties.getSummaryStartTurns();
        int keepTurns = memoryProperties.getHistoryKeepTurns();

        if (maxTurns <= keepTurns) {
            return;
        }

        String chatMessageKey = RedisKeyConfig.userConversationRecord(userId, conversationId);
        RScoredSortedSet<String> scoredSet = redissonClient.getScoredSortedSet(chatMessageKey);

        // 1. 保存当前消息
        scoredSet.add(System.currentTimeMillis(), JSON.toJSONString(message));
        // 失效Caffeine缓存
        localCache.invalidate(chatMessageKey);

        // 2. 异步保存到数据库
        kafkaTemplate.send("memory-cmd", null, new MemoryEvent(
                UUID.randomUUID().toString(),"SAVE_MESSAGE",
                conversationId,userId,JSON.toJSONString(message)));

        // 3. 检查是否需要压缩
        int total = scoredSet.size();
        if (total < maxTurns) {
            return;
        }

        int toCompressCount = total - keepTurns;
        if (toCompressCount <= 0) {
            return;
        }

        // 4. 获取最早的要压缩的消息
        Collection<String> toCompress = scoredSet.valueRange(0, toCompressCount - 1);
        if (toCompress == null || toCompress.isEmpty()) {
            return;
        }

        // 5. 删除已被压缩的消息 (删)
        scoredSet.removeAll(toCompress);
        // 失效Caffeine缓存
        localCache.invalidate(chatMessageKey);

        // 6. 提交摘要生成任务到Kafka
        String summaryJson = JSON.toJSONString(toCompress);
        kafkaTemplate.send("memory-cmd", String.valueOf(conversationId),
                new MemoryEvent(UUID.randomUUID().toString(), "GENERATE_SUMMARY",
                        conversationId, userId, summaryJson));
    }

    /**
     * 从 MemoryEvent 生成摘要（供 MemoryConsumer 调用）
     */
    public void generateAndSaveSummary(MemoryEvent event) {
        Long conversationId = event.getConversationId();
        Long userId = event.getUserId();

        // 从 Redis 获取现有摘要
        String summaryKey = RedisKeyConfig.userSummaryRecord(userId, conversationId);
        RBucket<String> summaryBucket = redissonClient.getBucket(summaryKey);
        String existingSummary = summaryBucket.get();

        // 解析需要压缩的消息列表
        Collection<String> toCompress = JSON.parseObject(
                event.getMessageJson(), Collection.class);

        generateAndSaveSummary(conversationId, null, userId, existingSummary, toCompress);
    }

    /**
     * 异步生成摘要并保存（使用 summaryGeneratorExecutor）
     */
    public void generateAndSaveSummary(Long conversationId, Long chatMessageId, Long userId,
                                       String existingSummary, Collection<String> toCompress) {
        String newSummary = generateSummary(existingSummary, toCompress, conversationId, chatMessageId, userId);
        if (newSummary != null && !newSummary.equals(existingSummary)) {
            // 保存摘要到 Redis
            String summaryKey = RedisKeyConfig.userSummaryRecord(userId, conversationId);
            RBucket<String> summaryBucket = redissonClient.getBucket(summaryKey);
            summaryBucket.set(newSummary);

            // 同步修改Caffeine缓存
            localCache.put(summaryKey, newSummary);

            // 更新数据库（异步，继续使用 summaryGeneratorExecutor 或 memoryCompactExecutor）
            UpdateWrapper<ChatSessionRecord> wrapper = new UpdateWrapper<>();
            wrapper.eq("conversation_id", conversationId).set("summary_text", newSummary);
            chatSessionRecordMapper.update(null, wrapper);
        }
    }

    /**
     * 调用 LLM 生成摘要（优化后的提示词）
     */
    private String generateSummary(String existingSummary, Collection<String> toCompress,
                                   Long conversationId, Long chatMessageId, Long userId) {
        try {
            String historyText = formatMessagesForSummary(toCompress);
            String existing = (existingSummary == null || existingSummary.isBlank())
                    ? SUMMARY_PLACEHOLDER
                    : existingSummary;
            String systemMessage = String.format(ConversationMemorySummaryService.systemMessage, memoryProperties.getSummaryMaxChars());
            String userMessage = String.format(ConversationMemorySummaryService.userMessage, existing, historyText);
            Prompt prompt = new Prompt(
                    new SystemMessage(systemMessage),
                    new UserMessage(userMessage));
            long startTime = System.currentTimeMillis();
            String summary = chatModel.call(prompt).getResult().getOutput().getText();
            long durationMs = System.currentTimeMillis() - startTime;
            // 记录 Token 使用
            RagTraceContext.setPhase("对话摘要");
            TokenUse tokenUse = new TokenUse(
                    conversationId,
                    chatMessageId,
                    prompt.toString().length(),
                    summary.length(),
                    userId,
                    durationMs,
                    chatModel.getDefaultOptions().getModel(),
                    "summary");
            kafkaTemplate.send("analytics-event", null, new AnalyticsEvent(
                    UUID.randomUUID().toString(), "evaluate",
                    null, tokenUse, null
            ));
            log.debug("摘要生成完成，耗时: {}ms", durationMs);
            return summary.isBlank() ? existingSummary : summary;

        } catch (Exception e) {
            log.error("生成摘要失败", e);
            return existingSummary;
        }
    }

    /**
     * 格式化消息用于摘要输入
     */
    private String formatMessagesForSummary(Collection<String> messages) {
        return messages.stream()
                .map(json -> {
                    try {
                        ChatMessage msg = JSON.parseObject(json, ChatMessage.class);
                        return String.format("用户：%s | AI：%s",
                                msg.getUserMessage(), msg.getAssistantMessage());
                    } catch (Exception e) {
                        return "";
                    }
                })
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n"));
    }

    // ==================== 数据库操作 ====================

    /**
     * 异步保存会话数据到数据库
     */
    public void asyncSaveToDatabase(Long conversationId, ChatMessage message) {
        saveOrUpdateSessionRecord(conversationId, message);
        saveConversationMessage(conversationId, message);
        cleanupOldMessages(conversationId);
    }

    private void saveOrUpdateSessionRecord(Long conversationId, ChatMessage message) {
        ChatSessionRecord existing = chatSessionRecordMapper.selectById(conversationId);
        if (existing != null) {
            return;
        }

        String title = extractTitle(message.getUserMessage());
        ChatSessionRecord record = new ChatSessionRecord();
        record.setConversationId(conversationId);
        record.setTitle(title);
        record.setUserId(message.getUserId());
        record.setCreatedAt(LocalDateTime.now().withNano(0));

        try {
            chatSessionRecordMapper.insert(record);
        } catch (DuplicateKeyException e) {
            log.debug("会话记录已存在: conversationId={}", conversationId);
        }
    }

    /**
     * 提取会话标题（优化：按字符数截断，防止乱码）
     */
    private String extractTitle(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "新对话";
        }
        String trimmed = userMessage.trim();
        if (trimmed.length() > MAX_TITLE_LENGTH) {
            return trimmed.substring(0, MAX_TITLE_LENGTH) + "...";
        }
        return trimmed;
    }

    private void saveConversationMessage(Long conversationId, ChatMessage message) {
        ChatConversation conversation = new ChatConversation();
        conversation.setChatMessageId(message.getChatMessageId());
        conversation.setUserId(message.getUserId());
        conversation.setConversationId(conversationId);
        conversation.setUserMessage(message.getUserMessage());
        conversation.setAssistantMessage(message.getAssistantMessage());
        conversation.setCreatedAt(LocalDateTime.now().withNano(0));
        try {
            chatConversationMapper.insert(conversation);
        } catch (DuplicateKeyException e) {
            log.debug("消息已存在: chatMessageId={}", message.getChatMessageId());
        }
    }

    /**
     * 清理旧消息：每个会话最多保留 MAX_DB_MESSAGES 条记录
     */
    private void cleanupOldMessages(Long conversationId) {
        try {
            // 查询当前会话的消息总数
            Long count = chatConversationMapper.selectCount(
                    new LambdaQueryWrapper<ChatConversation>()
                            .eq(ChatConversation::getConversationId, conversationId));
            if (count == null) return;

            int deleteCount = (int) Math.max(0, count - MAX_DB_MESSAGES);
            if (deleteCount > 0) {
                // 删除最旧的 deleteCount 条消息（依赖 Mapper 中实现的方法）
                chatConversationMapper.deleteOldestMessages(conversationId, deleteCount);
                log.debug("清理旧消息: conversationId={}, deleteCount={}", conversationId, deleteCount);
            }
        } catch (Exception e) {
            log.warn("清理旧消息失败: conversationId={}", conversationId, e);
        }
    }
}
