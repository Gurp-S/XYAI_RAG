package com.XYai.myai.rag.memory;

import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.mapper.ChatSessionRecordMapper;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.chat.pojo.ChatMessage;
import com.XYai.myai.rag.memory.pojo.ChatConversation;
import com.XYai.myai.rag.memory.pojo.ChatSessionRecord;
import com.XYai.myai.rag.memory.pojo.LoadSession;
import com.XYai.myai.rag.memory.pojo.MemoryProperties;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.user.LoginUserInfoManager;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 会话记忆与摘要服务类（无锁优化版）
 * 核心思想：允许重复压缩，通过幂等性保证最终一致性
 */
@Slf4j
@Service
public class ConversationMemorySummaryService {

    private static final String SUMMARY_PLACEHOLDER = "无";
    private static final int MAX_TITLE_LENGTH = 10;
    private static final int MAX_DB_RECORDS = 10;

    // 压缩状态标记（防止同一会话并发压缩）
    private final Set<String> compressingKeys = ConcurrentHashMap.newKeySet();

    @Resource
    private MemoryProperties memoryProperties;

    @Resource
    private ChatModel chatModel;

    @Resource
    private ChatSessionRecordMapper chatSessionRecordMapper;

    @Resource
    private ChatConversationMapper chatConversationMapper;

    @Resource
    private RedissonClient redissonClient;

    @Resource(name = "memeryExecutor")
    private ThreadPoolTaskExecutor memoryCompactExecutor;

    // ==================== 公共 API ====================

    /**
     * 判断是否需要触发摘要压缩（异步入口，无锁）
     */
    @RagTraceNode(name = "会话记忆压缩", type = "记忆压缩")
    public void compressIfNeeded(String conversationId, ChatMessage message) {
        if (!memoryProperties.getSummaryEnabled()) {
            return;
        }

        Long userId = extractUserId(message);
        if (userId == null) {
            log.warn("compressIfNeeded: 无法获取用户ID, conversationId={}", conversationId);
            return;
        }

        // 使用 Set 防止同一会话并发压缩（轻量级，无锁）
        String compressKey = conversationId + ":" + userId;
        if (!compressingKeys.add(compressKey)) {
            log.debug("压缩任务已在执行中，跳过: {}", compressKey);
            return;
        }

        memoryCompactExecutor.execute(() -> {
            try {
                doCompressIfNeeded(conversationId, message, userId);
            } catch (Exception e) {
                log.error("压缩任务执行失败: conversationId={}", conversationId, e);
            } finally {
                compressingKeys.remove(compressKey);
            }
        });
    }

    /**
     * 加载会话上下文
     */
    @RagTraceNode(name = "加载会话记忆", type = "记忆加载")
    public LoadSession load(String conversationId) {
        Long userId = LoginUserInfoManager.getUserId();
        if (userId == null) {
            return LoadSession.fromCollection(null, Collections.emptySet());
        }

        String conversationKey = RedisKeyConfig.userConversationRecord(userId, conversationId);
        String summaryKey = RedisKeyConfig.userSummaryRecord(userId, conversationId);

        RScoredSortedSet<String> scoredSet = redissonClient.getScoredSortedSet(conversationKey);
        RBucket<String> summaryBucket = redissonClient.getBucket(summaryKey);

        Collection<String> convoObjs = scoredSet.valueRange(0, -1);
        Set<ChatMessage> conversations = parseConversations(convoObjs);
        String summary = summaryBucket.get();

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
     * 执行压缩（无锁，使用幂等设计）
     */
    private void doCompressIfNeeded(String conversationId, ChatMessage message, Long userId) {
        int maxTurns = memoryProperties.getSummaryStartTurns();
        int keepTurns = memoryProperties.getHistoryKeepTurns();

        if (maxTurns <= keepTurns) {
            return;
        }

        String chatMessageKey = RedisKeyConfig.userConversationRecord(userId, conversationId);
        RScoredSortedSet<String> scoredSet = redissonClient.getScoredSortedSet(chatMessageKey);

        // 1. 先保存当前消息
        scoredSet.add(System.currentTimeMillis(), JSON.toJSONString(message));

        // 2. 异步保存到数据库（不阻塞）
        asyncSaveToDatabase(conversationId, message);

        // 3. 检查是否需要压缩（基于当前大小）
        long total = scoredSet.size();
        if (total < maxTurns) {
            return;
        }

        // 4. 计算需要压缩的数量
        int toCompressCount = maxTurns - keepTurns;
        if (toCompressCount <= 0) {
            return;
        }

        // 5. 原子性取出并删除（Redis 的 ZRANGE + ZREMRANGE 不是原子的，但允许重复压缩）
        //    重复压缩时，可能取到的消息变少，通过幂等性保证最终一致
        Collection<String> toCompress = scoredSet.valueRange(0, toCompressCount - 1);
        if (toCompress == null || toCompress.isEmpty()) {
            return;
        }

        // 删除已被压缩的消息
        scoredSet.removeAll(toCompress);

        // 6. 获取现有摘要
        String summaryKey = RedisKeyConfig.userSummaryRecord(userId, conversationId);
        RBucket<String> summaryBucket = redissonClient.getBucket(summaryKey);
        String existingSummary = summaryBucket.get();

        // 7. 生成新摘要（异步，不阻塞）
        generateAndSaveSummary(conversationId, userId, existingSummary, toCompress);
    }

    /**
     * 异步生成并保存摘要
     */
    private void generateAndSaveSummary(String conversationId, Long userId,
                                        String existingSummary, Collection<String> toCompress) {
        memoryCompactExecutor.execute(() -> {
            try {
                String newSummary = generateSummary(existingSummary, toCompress);
                if (newSummary != null && !newSummary.equals(existingSummary)) {
                    // 保存摘要
                    String summaryKey = RedisKeyConfig.userSummaryRecord(userId, conversationId);
                    RBucket<String> summaryBucket = redissonClient.getBucket(summaryKey);
                    summaryBucket.set(newSummary);

                    // 更新数据库
                    asyncUpdateSummary(conversationId, newSummary);
                }
            } catch (Exception e) {
                log.error("生成摘要失败: conversationId={}", conversationId, e);
            }
        });
    }

    /**
     * 生成摘要（调用 LLM）
     */
    private String generateSummary(String existingSummary, Collection<String> toCompress) {
        try {
            String historyText = formatMessagesForSummary(toCompress);
            String existing = (existingSummary == null || existingSummary.isBlank())
                    ? SUMMARY_PLACEHOLDER : existingSummary;

            String systemMessage = String.format(
                    "合并摘要。要求：≤%d字，去寒暄，纯文本。",
                    memoryProperties.getSummaryMaxChars());
            String userMessage = String.format("""
                    历史摘要（参考，不要复述）:
                    %s
                    新对话:
                    %s
                    请输出更新后的摘要:
                    """, existing, historyText);

            Prompt prompt = new Prompt(
                    new SystemMessage(systemMessage),
                    new UserMessage(userMessage)
            );

            long startTime = System.currentTimeMillis();
            String raw = chatModel.call(prompt).getResult().getOutput().getText();
            long duration = System.currentTimeMillis() - startTime;

            log.debug("摘要生成完成，耗时: {}ms, conversationId", duration);
            return (raw == null || raw.isBlank()) ? existingSummary : raw;

        } catch (Exception e) {
            log.error("生成摘要失败", e);
            return existingSummary;
        }
    }

    /**
     * 格式化消息用于摘要
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
                .collect(Collectors.joining("；"));
    }

    /**
     * 异步保存到数据库
     */
    private void asyncSaveToDatabase(String conversationId, ChatMessage message) {
        memoryCompactExecutor.execute(() -> {
            try {
                saveOrUpdateSessionRecord(conversationId, message);
                saveConversationMessage(conversationId, message);
                cleanupOldRecords(conversationId);
            } catch (Exception e) {
                log.error("保存会话到数据库失败: conversationId={}", conversationId, e);
            }
        });
    }

    private void saveOrUpdateSessionRecord(String conversationId, ChatMessage message) {
        ChatSessionRecord existing = chatSessionRecordMapper.selectById(conversationId);
        if (existing != null) return;

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

    private String extractTitle(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) return "新对话";
        String trimmed = userMessage.trim();
        return trimmed.length() > MAX_TITLE_LENGTH ? trimmed.substring(0, MAX_TITLE_LENGTH) : trimmed;
    }

    private void saveConversationMessage(String conversationId, ChatMessage message) {
        ChatConversation conversation = new ChatConversation();
        conversation.setChatMessageId(UUID.randomUUID().toString());
        conversation.setUserId(message.getUserId());
        conversation.setConversationId(conversationId);
        conversation.setUserMessage(message.getUserMessage());
        conversation.setAssistantMessage(message.getAssistantMessage());
        conversation.setCreatedAt(LocalDateTime.now().withNano(0));
        chatConversationMapper.insert(conversation);
    }

    private void cleanupOldRecords(String conversationId) {
        try {
            long total = chatSessionRecordMapper.countByConversationId(conversationId);
            int deleteCount = (int) Math.max(0, total - MAX_DB_RECORDS);
            if (deleteCount > 0) {
                chatSessionRecordMapper.deleteOldestByLimit(conversationId, deleteCount);
            }
        } catch (Exception e) {
            log.warn("清理过期记录失败: conversationId={}", conversationId, e);
        }
    }

    private void asyncUpdateSummary(String conversationId, String summary) {
        memoryCompactExecutor.execute(() -> {
            try {
                UpdateWrapper<ChatSessionRecord> wrapper = new UpdateWrapper<>();
                wrapper.eq("conversation_id", conversationId).set("summary_text", summary);
                chatSessionRecordMapper.update(null, wrapper);
            } catch (Exception e) {
                log.error("更新摘要失败: conversationId={}", conversationId, e);
            }
        });
    }
}