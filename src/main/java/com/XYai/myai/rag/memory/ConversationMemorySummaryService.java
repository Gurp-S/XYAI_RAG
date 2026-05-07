package com.XYai.myai.rag.memory;

import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.mapper.ChatSessionRecordMapper;
import com.XYai.myai.rag.chat.POJO.ChatMessage;
import com.XYai.myai.rag.memory.POJO.ChatConversation;
import com.XYai.myai.rag.memory.POJO.ChatSessionRecord;
import com.XYai.myai.rag.memory.POJO.LoadSession;
import com.XYai.myai.rag.memory.POJO.MemoryProperties;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.user.LoginUserInfoManager;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
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
import java.util.concurrent.TimeUnit;

/**
 * 会话记忆与摘要服务类。
 * 负责管理用户聊天记录的持久化，并利用 AI 异步生成历史会话摘要，以优化长对话的 token 使用。
 */
@Slf4j
@Service
public class ConversationMemorySummaryService {
    @Resource
    private MemoryProperties memoryProperties;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private ChatModel chatModel;
    @Resource
    private ChatSessionRecordMapper chatSessionRecordMapper;
    @Resource
    private ChatConversationMapper chatConversationMapper;
    @Resource
    private RedissonClient redissonClient;
    // 使用统一的内存压缩执行器（在 ThreadPoolConfig 中定义为 memoryCompactExecutor）
    @Resource(name = "memeryExecutor")
    private ThreadPoolTaskExecutor memoryCompactExecutor;

    // TODO streamLLM

    /**
     * 根据会话 ID 与新消息判断是否需要触发摘要压缩。
     * 该方法为异步入口：当满足条件且开启摘要功能时，会在后台异步执行压缩流程，避免阻塞主线程。
     *
     * @param conversationId 会话 ID
     * @param message        新的聊天消息
     */
    public void compressIfNeeded(String conversationId, ChatMessage message) {

        // 条件1：摘要功能开启
        if (!memoryProperties.getSummaryEnabled()) {
            return;
        }

        log.info("compressIfNeeded: {}", message);

        // 优先使用传入 message 中的 userId（调用方在请求线程中已知 userId）
        // 作为回退再尝试从线程上下文获取（ThreadLocal）。这样可以避免在异步/流式
        // 执行中因为 SecurityContext 被清理而导致无法获取到用户信息的问题。
        Long userId;
        if (message != null && message.getUserId() != null) {
            userId = message.getUserId();
        } else {
            var loginUser = LoginUserInfoManager.get();
            if (loginUser != null) {
                userId = loginUser.getId();
            } else {
                userId = null;
            }
        }

        if (userId == null) {
            log.warn("compressIfNeeded: 用户没有登录且 message.userId 为空,当前对话={}", conversationId);
            return;
        }
        // 执行压缩
        memoryCompactExecutor.execute(() -> {
            try {
                doCompressIfNeeded(conversationId, message, userId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * 真正执行压缩的内部方法（在异步线程中运行）。
     * 步骤：
     * 1. 获取并检查压缩触发阈值；
     * 2. 获取分布式锁以防并发压缩；
     * 3. 保存新消息并判断是否达到压缩阈值；
     * 4. 调用 LLM 生成摘要并持久化。
     *
     * @param conversationId 会话 ID
     * @param message        待处理的聊天消息
     * @param userId
     */
    private void doCompressIfNeeded(String conversationId, ChatMessage message, Long userId) throws InterruptedException {
        // ========== 步骤1：前置条件检查 ==========
        int maxTurns = memoryProperties.getSummaryStartTurns(); // 达到该轮数才开始压缩

        // ========== 步骤2：分布式锁（防止并发压缩，避免数据冲突） =========
        String lockKey = RedisKeyConfig.userConversationLock(userId, conversationId);
        RLock lock = redissonClient.getLock(lockKey);
        if (!lock.tryLock(0, 30, TimeUnit.SECONDS))
            return;

        try {
            // ========== 步骤3：先保存再判断是否需要压缩 ==========
            String chatMessageKey = RedisKeyConfig.userConversationRecord(userId, conversationId);
            // 先保存新消息（每轮都保存） -- Redis 统一存 JSON 字符串
            RScoredSortedSet<String> scoredSet = redissonClient.getScoredSortedSet(chatMessageKey);
            scoredSet.add((double) System.currentTimeMillis(), JSON.toJSONString(message));
            // 持久化到 DB 使用受管线程池执行，避免使用 CompletableFuture.runAsync 造成线程不可控
            recordSessionDB(conversationId, message);
            // 再判断是否需要压缩
            long total = scoredSet.size();
            if (total < maxTurns) {
                return;
            }

            // ========== 步骤4：获取已有的摘要 ==========
            String summaryKey = RedisKeyConfig.userSummaryRecord(userId, conversationId);
            RBucket<String> summaryBucket = redissonClient.getBucket(summaryKey);
            String latestSummary = summaryBucket.get();

            // ========== 步骤5：提取要压缩的消息,并删除 ==========
            // 保留最近 4 轮，压缩更早的4轮消息
            Collection<String> firstBatch = scoredSet.valueRange(0, maxTurns - memoryProperties.getHistoryKeepTurns() - 1);
            if (firstBatch == null || firstBatch.isEmpty()) {
                return;
            }
            // 删除历史对话
            scoredSet.removeAll(firstBatch);

            // ========== 步骤6：调用 LLM 生成摘要 ==========
            String existingSummary = latestSummary == null ? "无" : latestSummary;
            String summary = summarizeMessages(existingSummary, firstBatch);


            // ========== 步骤7：存储摘要 ==========
            summaryBucket.set(summary);
            // 使用受管线程池异步更新 DB
            upsetSummary(conversationId, summary);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            } // 释放锁，避免死锁
        }
    }

    /**
     * 调用大模型对给定的历史摘要与待压缩消息进行合并与去重，输出新的摘要文本。
     *
     * @param existingSummary 现有的摘要文本（可能为null或空）
     * @param toSummary       需要被压缩的对话片段（JSON 字符串形式）
     * @return 新的摘要文本
     * @throws JsonProcessingException 当序列化/反序列化失败时抛出
     */
    private String summarizeMessages(String existingSummary, Collection<String> toSummary) throws JsonProcessingException {
        // 如果有旧摘要，追加进去（增量合并，避免重复)
        SummaryMessage summaryMessage = new SummaryMessage();
        summaryMessage.setLastestSummary("历史摘要（仅用于合并去重，不得作为事实新增来源):" + existingSummary.trim());
        summaryMessage.setChatMessage("对话:" + toSummary);
        String historyToSummary = toSummary.stream()
                .map(json -> JSON.parseObject(json, ChatMessage.class))  // 转 ChatMessage
                .map(chatMsg -> "用户：" + (chatMsg.getUserMessage() == null ? "" : chatMsg.getUserMessage())
                        + " | AI：" + (chatMsg.getAssistantMessage() == null ? "" : chatMsg.getAssistantMessage()))
                .reduce((msg1, msg2) -> msg1 + "；" + msg2)  // 拼接成一行
                .orElse("无对话内容");
        String systemMessage = "合并摘要。要求：≤%d字，去寒暄，纯文本。".formatted(memoryProperties.getSummaryMaxChars());
        String userMessage = """
                历史摘要（参考，不要复述）:
                %s
                新对话:
                %s
                请输出更新后的摘要:
                """.formatted(existingSummary, historyToSummary);
        Prompt prompt = new Prompt(
                new SystemMessage(systemMessage),
                new UserMessage(userMessage));
        String raw = chatModel.call(prompt).getResult().getOutput().getText();
        return raw == null ? existingSummary : raw;
    }

    /**
     * 将新地聊天消息与会话元信息持久化到数据库：
     * 1. 若会话元信息不存在则创建 ChatSessionRecord；
     * 2. 将聊天消息插入 ChatConversation 表；
     * 3. 保持每个会话只保留有限条数的历史记录（超出则删除最旧）
     *
     * @param ConversationId 会话 ID
     * @param message        要保存的聊天消息
     */
    private void recordSessionDB(String ConversationId, ChatMessage message) {
        try {
            String title = message.getUserMessage().trim();
            title = title.length() > 10 ? title.substring(0, 10) : title;
            // 如果会话元数据不存在，则创建
            ChatSessionRecord sessionIsSave = chatSessionRecordMapper.selectById(ConversationId);
            if (sessionIsSave == null) {
                ChatSessionRecord record = new ChatSessionRecord();
                record.setConversationId(ConversationId);
                record.setTitle(title);
                record.setUserId(message.getUserId());
                record.setCreatedAt(LocalDateTime.now().withNano(0));
                try {
                    chatSessionRecordMapper.insert(record);
                } catch (DuplicateKeyException e) {
                    // 并发时另一个线程已插入该 conversationId，忽略
                    log.debug("另一个线程已插入 chat session record，conversationId={}", ConversationId);
                }
            }
            ChatConversation conversation = new ChatConversation();
            conversation.setUserId(message.getUserId());
            conversation.setConversationId(ConversationId);
            conversation.setUserMessage(message.getUserMessage());
            conversation.setAssistantMessage(message.getAssistantMessage());
            conversation.setCreatedAt(LocalDateTime.now().withNano(0));
            conversation.setChatMessageId(UUID.randomUUID().toString());
            chatConversationMapper.insert(conversation);
            // 每个会话最多保留最近记录，超出自动删除最旧记录
            long total = chatSessionRecordMapper.countByConversationId(ConversationId);
            int deleteCount = (int) Math.max(0, total - 10);
            if (deleteCount > 0) {
                chatSessionRecordMapper.deleteOldestByLimit(ConversationId, deleteCount);
            }
        } catch (Exception e) {
            log.error("保存会话到数据库失败", e);
        }
    }

    /**
     * 更新会话元数据中的摘要字段（DB 更新）。
     *
     * @param conversationId 会话 ID
     * @param summary        新的摘要文本
     */
    private void upsetSummary(String conversationId, String summary) {
        UpdateWrapper<ChatSessionRecord> wrapper = new UpdateWrapper<>();
        wrapper.eq("conversation_id", conversationId).set("summary_text", summary);
        chatSessionRecordMapper.update(wrapper);
    }

    /**
     * 加载给定会话的上下文数据：包括已保存的聊天消息集合与摘要文本。
     *
     * @param conversationId 会话 ID
     * @return LoadSession 包含 summary 与 conversation（消息集合）
     */
    public LoadSession load(String conversationId) {
        // 获取上下文对话和摘要
        long userId = LoginUserInfoManager.get().getId();
        String conversationKey = RedisKeyConfig.userConversationRecord(userId, conversationId);
        String summaryKey = RedisKeyConfig.userSummaryRecord(userId, conversationId);
        RScoredSortedSet<String> scoredSet = redissonClient.getScoredSortedSet(conversationKey);
        Collection<String> convoObjs = scoredSet.valueRange(0, -1);
        Set<ChatMessage> conversations;
        if (convoObjs == null || convoObjs.isEmpty()) {
            conversations = Collections.emptySet();
        } else {
            Set<ChatMessage> tmp = new LinkedHashSet<>();
            for (String convoJson : convoObjs) {
                tmp.add(JSON.parseObject(convoJson, ChatMessage.class));
            }
            conversations = tmp;
        }
        RBucket<String> summaryBucket = redissonClient.getBucket(summaryKey);
        String summary = summaryBucket.get();
        return LoadSession.fromCollection(summary, conversations);
    }
}