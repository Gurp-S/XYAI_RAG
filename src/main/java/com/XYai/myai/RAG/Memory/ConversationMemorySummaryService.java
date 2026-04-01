package com.XYai.myai.RAG.Memory;

import com.XYai.myai.Chat.ChatMessage;
import com.XYai.myai.User.LoginUserInfoManager;
import com.XYai.myai.mapper.ChatConversationMapper;
import com.XYai.myai.mapper.ChatSessionRecordMapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ConversationMemorySummaryService {
    @Resource
    private MemoryProperties memoryProperties;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
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
    //TODO streamLLM
    /**
     * 根据会话 ID 与新消息判断是否需要触发摘要压缩。
     * 该方法为异步入口：当满足条件且开启摘要功能时，会在后台异步执行压缩流程，避免阻塞主线程。
     *
     * @param conversationId 会话 ID
     * @param message 新的聊天消息
     */
    public void compressIfNeeded(String conversationId, ChatMessage message) {

        // 条件1：摘要功能开启
        if (!memoryProperties.getSummaryEnabled()) {
            return;
        }
        // 异步执行压缩，不阻塞主流程（关键：避免影响用户交互响应速)
        CompletableFuture.runAsync(Objects.requireNonNull(() -> {
            try {
                doCompressIfNeeded(conversationId, message,LoginUserInfoManager.getId());
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }));
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
     * @param message 待处理的聊天消息
     * @param userId 用户 ID（用于写入会话元信息）
     */
    private void doCompressIfNeeded(String conversationId, ChatMessage message,Long userId) throws InterruptedException {

        // ========== 步骤1：前置条件检查 ==========
        int maxTurns = memoryProperties.getSummaryStartTurns(); // 达到该轮数才开始压缩

        // ========== 步骤2：分布式锁（防止并发压缩，避免数据冲突） ==========
        String lockKey = "summary:lock:" + conversationId;
        RLock lock = redissonClient.getLock(lockKey);
        if (!lock.tryLock(0, 30, TimeUnit.SECONDS)) return;

        try {
            // ========== 步骤3：先保存再判断是否需要压缩 ==========
            String chatMessageKey = "chatMessage:" + conversationId;
            // 先保存新消息（每轮都保存）
            String messageJson = objectMapper.writeValueAsString(message);
            stringRedisTemplate.opsForZSet().add(chatMessageKey, messageJson, System.currentTimeMillis());
            CompletableFuture.runAsync(() -> recordSessionDB(conversationId, message,userId));
            // 再判断是否需要压缩
            Long total = stringRedisTemplate.opsForZSet().size(chatMessageKey);
            if (total == null || total < maxTurns) {
                return;
            }

            // ========== 步骤4：获取已有的摘要 ==========
            String summaryKey = "summary:" + conversationId;
            String latestSummary = stringRedisTemplate.opsForValue().get(summaryKey);

            // ========== 步骤5：提取要压缩的消息,并删除 ==========
            // 保留最近 4 轮，压缩更早的消息
            Set<String> firstBatch = stringRedisTemplate.opsForZSet().range(chatMessageKey, 0, 0);
            if (firstBatch == null || firstBatch.isEmpty()) {
                return;
            }
            String toSummary = firstBatch.iterator().next();
            stringRedisTemplate.opsForZSet().popMin(chatMessageKey);

            // ========== 步骤7：调用 LLM 生成摘要 ==========
            String existingSummary = latestSummary == null ? "无" : latestSummary;
            String summary = summarizeMessages(existingSummary, toSummary);

            // ========== 步骤8：存储摘要 ==========
            stringRedisTemplate.opsForValue().set(summaryKey, summary);
            CompletableFuture.runAsync(() -> upsetSummary(conversationId, summary));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }// 释放锁，避免死锁
        }
    }
    /**
     * 调用大模型对给定的历史摘要与待压缩消息进行合并与去重，输出新的摘要文本。
     *
     * @param existingSummary 现有的摘要文本（可能为null或空）
     * @param toSummary 需要被压缩的对话片段（JSON 字符串形式）
     * @return 新的摘要文本
     * @throws JsonProcessingException 当序列化/反序列化失败时抛出
     */
    private String summarizeMessages(String existingSummary, String toSummary) throws JsonProcessingException {
        // 如果有旧摘要，追加进去（增量合并，避免重复)
        SummaryMessage summaryMessage = new SummaryMessage();
        summaryMessage.setLastestSummary("历史摘要（仅用于合并去重，不得作为事实新增来源):" + existingSummary.trim());
        summaryMessage.setChatMessage("对话:" + toSummary);
        String summaryMessageJson = objectMapper.writeValueAsString(summaryMessage);
        String SystemMessage = ("合并以上对话与历史摘要，去重后输出更新摘要。\n" +
                "要求：严格≤" + memoryProperties.getSummaryMaxChars() + "字符；仅一行。格式:原本的JSON格式");
        Prompt prompt = new Prompt(
                new SystemMessage(SystemMessage),
                new UserMessage(summaryMessageJson)
        );
        return chatModel.call(prompt).getResult().getOutput().getText();
    }
    /**
     * 将新的聊天消息与会话元信息持久化到数据库：
     * 1. 若会话元信息不存在则创建 ChatSessionRecord；
     * 2. 将聊天消息插入 ChatConversation 表；
     * 3. 保持每个会话只保留有限条数的历史记录（超出则删除最旧）。
     *
     * @param ConversationId 会话 ID
     * @param message 要保存的聊天消息
     * @param userId 发起用户 ID
     */
    private void recordSessionDB(String ConversationId, ChatMessage message,Long userId) {
        try {
            String title = message.getUserMessage().trim();
            title = title.length() > 10 ? title.substring(0, 10) : title;
            // 如果会话元数据不存在，则创建
            ChatSessionRecord sessionIsSave = chatSessionRecordMapper.selectById(ConversationId);
            if (sessionIsSave == null) {
                ChatSessionRecord record = new ChatSessionRecord();
                record.setConversationId(ConversationId);
                record.setTitle(title);
                record.setUserId(userId);
                record.setCreatedAt(LocalDateTime.now().withNano(0));
                try {
                    chatSessionRecordMapper.insert(record);
                } catch (DuplicateKeyException e) {
                    // 并发时另一个线程已插入该 conversationId，忽略
                    log.debug("另一个线程已插入 chat session record，conversationId={}", ConversationId);
                }
            }
            ChatConversation conversation = new ChatConversation();
            conversation.setConversationId(ConversationId);
            conversation.setUserMessage(message.getUserMessage());
            conversation.setAssistantMessage(message.getAssistantMessage());
            conversation.setCreatedAt(LocalDateTime.now().withNano(0));
            conversation.setChatMessageId(UUID.randomUUID().toString());
            chatConversationMapper.insert(conversation);
            // 每个会话最多保留最近记录，超出自动删除最旧记录TODO删除对话
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
     * @param summary 新的摘要文本
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
        String conversationKey = "chatMessage:" + conversationId;
        String summaryKey = "summary:" + conversationId;
        Set<String> conversations = stringRedisTemplate.opsForZSet().range(conversationKey, 0, -1);
        String summary = stringRedisTemplate.opsForValue().get(summaryKey);
        return LoadSession.builder().summary(summary).conversation(conversations).build();
    }
}