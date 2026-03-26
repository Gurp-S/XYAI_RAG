package com.XYai.myai.core.memory;

import com.XYai.myai.core.dto.ChatMemorySummary;
import com.XYai.myai.core.dto.SessionRoundDto;
import com.XYai.myai.mapper.ChatMemorySummaryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的会话记忆存储实现。
 *
 * <p>当前实现按会话 ID 维护有序对话历史，并在达到阈值后触发摘要压缩。</p>
 */
@Slf4j
@Service
public class RedisMemoryStore implements MemoryStore{

    private final Long COMPACTTRIGGERTURNS = 4L;
    // StreamingLLM 核心：保留首轮对话不动，充当 Attention Sink（注意力锚点）
    private final Long ATTENTION_SINK_TURNS = 1L;

    private final int TTLHOURS = 72 ;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private MemoryCompressor memoryCompressor;
    @Resource
    private ChatMemorySummaryMapper chatMemorySummaryMapper;
    @Resource(name = "memoryCompactExecutor")
    private Executor memoryCompactExecutor;

    /**
     * 追加一轮会话到 Redis 有序集合。
     *
     * @param conversationId 会话 ID，用于隔离不同用户/会话的记忆
     * @param userUtterance 用户本轮输入
     * @param botResponse AI 本轮输出
     */
    @Override
    public void addInteraction(String conversationId, String userUtterance, String botResponse) {
        // 基础参数校验。
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("会话ID为空");
        }
        if (userUtterance == null || userUtterance.isBlank() || botResponse == null || botResponse.isBlank()) {
            throw new IllegalArgumentException("消息为空");
        }
        String key = "Chat:Mem:{cid}:recent" + conversationId;
        // 会话轮次序列化。
        SessionRoundDto roundDto = SessionRoundDto.now(userUtterance, botResponse);
        String roundJson;
        try {
            roundJson = objectMapper.writeValueAsString(roundDto);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("会话轮次序列化失败", e);
        }
        // 使用毫秒时间戳做 score，避免同秒覆盖/排序混乱。
        double score = (double) roundDto.ts();
        stringRedisTemplate.opsForZSet().add(key, roundJson, score);
        // 设置 TTL，降低长期占用。
        stringRedisTemplate.expire(key, TTLHOURS, TimeUnit.HOURS);
        
        // 超过阈值后执行压缩(异步执行，防止大模型长耗时阻塞)
        CompletableFuture.runAsync(() -> {
            try {
                compactConversation(conversationId);
            } catch (Exception e) {
                log.error("失败压缩ID: {}", conversationId, e);
            }
        }, memoryCompactExecutor);
    }

    /**
     * 获取指定会话的上下文列表。
     *
     * @param conversationId 会话 ID
     * @return 当前会话对应的历史记录列表
     */
    @Override
    public List<String> getContext(String conversationId) {
        //空指针
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        //获取会话
        String key = "Chat:Mem:{cid}:recent" + conversationId;
        Set<String> session = stringRedisTemplate.opsForZSet().range(key, 0, -1);
        if (session == null || session.isEmpty()) {
            return List.of();
        }
        //拼接会话
        List<String> context = new ArrayList<>();
        for (String raw : session) {
            try {
                SessionRoundDto round = objectMapper.readValue(raw, SessionRoundDto.class);
                context.add("User: " + round.user());
                context.add("Assistant: " + round.assistant());
            } catch (Exception ignore) {
                context.add(raw);
            }
        }
        return context;
    }

    /**
     * 对超出阈值的历史进行压缩并更新摘要。
     *
     * @param conversationId 会话 ID
     */
    @Override
    public void compactConversation(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        String key = "Chat:Mem:{cid}:recent" + conversationId;
        Long size = stringRedisTemplate.opsForZSet().size(key);
        // 避免空指针
        // 当保存的元素大于配置的阈值 + 锚点保留值 时，才进行压缩
        if (size == null || size <= (COMPACTTRIGGERTURNS + ATTENTION_SINK_TURNS)) {
            return;
        }
        // 计算需要压缩并删除的结束索引（留下最后 COMPACTTRIGGERTURNS 条最新记录）
        long removeEndIndex = size - COMPACTTRIGGERTURNS - 1;
        // 获取要压缩的历史消息（！！！核心：从 ATTENTION_SINK_TURNS 开始，跳过第 0 条锚点 !!!）
        Set<String> ttlSession = stringRedisTemplate.opsForZSet().range(key, ATTENTION_SINK_TURNS, removeEndIndex);
        if (ttlSession == null || ttlSession.isEmpty()) {
            return;
        }
        // 我们需要把 JSON 字符串反序列化成对 AI 可读的标准文本格式，不要直接传 JSON 去 summary
        List<String> oldRoundsText = new ArrayList<>();
        for (String raw : ttlSession) {
            try {
                SessionRoundDto round = objectMapper.readValue(raw, SessionRoundDto.class);
                oldRoundsText.add("User: " + round.user() + "\nAssistant: " + round.assistant());
            } catch (Exception ignore) {
                oldRoundsText.add(raw);
            }
        }
        // 先删除再摘要（！！！核心删除保护：保留第 0 条，从 ATTENTION_SINK_TURNS 开始删除 !!!）
        stringRedisTemplate.opsForZSet().removeRange(key, ATTENTION_SINK_TURNS, removeEndIndex);
        // 生成摘要
        String summaryKey = key + "Summary";
        String existingSummary = stringRedisTemplate.opsForValue().get(summaryKey);
        try {
            existingSummary = memoryCompressor.summarize(existingSummary, oldRoundsText);
            stringRedisTemplate.opsForValue().set(summaryKey, existingSummary);
            upsertSummaryToDb(conversationId, existingSummary);
        } catch (Exception e) {
            log.error("摘要生成失败，已删除待压缩轮次", e);
            // 这里若失败，虽然旧对话已经在 ZSet 中被丢弃且摘要没更新到，但能防止整个会话的 ZSet 无限膨胀
        }
    }

    private void upsertSummaryToDb(String conversationId, String summaryText) {
        if (summaryText == null || summaryText.isBlank()) {
            return;
        }

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
}
