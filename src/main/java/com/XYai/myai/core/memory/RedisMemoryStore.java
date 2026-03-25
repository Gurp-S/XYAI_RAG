package com.XYai.myai.core.memory;

import com.XYai.myai.core.dto.SessionRoundDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的会话记忆存储实现。
 *
 * <p>当前实现按会话 ID 维护有序对话历史，并在达到阈值后触发摘要压缩。</p>
 */
@Service
public class RedisMemoryStore implements MemoryStore{

    private final Long COMPACTTRIGGERTURNS = 4L;

    private final int TTLHOURS = 72 ;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ObjectMapper objectMapper;
    @Resource
    private MemoryCompressor memoryCompressor;

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
            throw new IllegalStateException("failed to serialize session round", e);
        }
        // 使用毫秒时间戳做 score，避免同秒覆盖/排序混乱。
        double score = (double) roundDto.ts();
        stringRedisTemplate.opsForZSet().add(key, roundJson, score);
        // 设置 TTL，降低长期占用。
        stringRedisTemplate.expire(key, TTLHOURS, TimeUnit.HOURS);
        // 超过阈值后执行压缩。
        compactConversation(conversationId);
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
        if (size == null || size <= COMPACTTRIGGERTURNS) {
            return;
        }
        // 计算要保留的起始位置
        long removeEndIndex = size - COMPACTTRIGGERTURNS - 1;
        // 获取要压缩的历史消息
        Set<String> ttlSession = stringRedisTemplate.opsForZSet().range(key, 0, removeEndIndex);
        if (ttlSession == null || ttlSession.isEmpty()) {
            return;
        }
        // 生成摘要
        String summaryKey = key + "Summary";
        String existingSummary = stringRedisTemplate.opsForValue().get(summaryKey);
        existingSummary = memoryCompressor.summarize(existingSummary, new ArrayList<>(ttlSession));
        stringRedisTemplate.opsForValue().set(summaryKey, existingSummary);
        // 按索引范围删除
        stringRedisTemplate.opsForZSet().removeRange(key, 0, removeEndIndex);
    }
}