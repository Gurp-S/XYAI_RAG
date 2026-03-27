package com.XYai.myai.core.memory;

import com.XYai.myai.core.dto.ChatMemorySummary;
import com.XYai.myai.mapper.ChatMemorySummaryMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static com.XYai.myai.Service.iml.ChatServiceIml.SYSTEM_MESSAGE;

/**
 * 基于 Redis 的会话记忆存储实现。
 *
 * <p>当前实现按会话 ID 维护有序对话历史，并在达到阈值后触发摘要压缩。</p>
 */
@Slf4j
@Service
public class MemoryStoreService implements MemoryStore{

    private final Long COMPACTTRIGGERTURNS = 4L;
    // StreamingLLM 核心：保留首轮对话不动，充当 Attention Sink（注意力锚点）
    private final Long ATTENTION_SINK_TURNS = 1L;

    private final int TTLHOURS = 72 ;

    @Resource
    private StringRedisTemplate stringRedisTemplate;
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
        String context= "User: " + userUtterance + "\nAI: " + botResponse;
        // 使用毫秒时间戳做 score，避免同秒覆盖/排序混乱。
        stringRedisTemplate.opsForZSet().add(key, context, System.currentTimeMillis());
        // 设置 TTL，降低长期占用。
        stringRedisTemplate.expire(key, TTLHOURS, TimeUnit.HOURS);
        // 超过阈值后执行压缩(异步执行，防止大模型长耗时阻塞)
        CompletableFuture.runAsync(() -> {
            compactConversation(conversationId);
        }, memoryCompactExecutor);
    }

    /**
     * 获取指定会话的上下文列表。
     *
     * @param conversationId 会话 ID
     * @return 当前会话对应的历史记录列表
     */
    public List<String> getContext(String conversationId) {
        //空指针
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }
        //获取会话
        String key = "Chat:Mem:{cid}:recent" + conversationId;
        Set<String> sessions = stringRedisTemplate.opsForZSet().range(key, 0, -1);
        if (sessions == null || sessions.isEmpty()) {
            return List.of();
        }
        //拼接会话
        return new ArrayList<>(sessions);
    }

    /**
     * 对超出阈值的历史进行压缩并更新摘要。
     *
     * @param conversationId 会话 ID
     */
    public void compactConversation(String conversationId) {
        // 避免空指针
        if (conversationId == null || conversationId.isBlank())return;
        String key = "Chat:Mem:{cid}:recent" + conversationId;
        //保留前几个token streamLLm核心
        List<String> oldRoundsText = StreamLLM(conversationId);
        if (oldRoundsText == null) return;
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

    private List<String> StreamLLM(String key) {
        Long size = stringRedisTemplate.opsForZSet().size(key);
        // 当保存的元素大于配置的阈值 + 锚点保留值 时，进行压缩
        if (size == null || size <= (COMPACTTRIGGERTURNS + ATTENTION_SINK_TURNS)) {
            return null;
        }
        // 计算需要压缩并删除的结束索引（留下最后 COMPACTTRIGGERTURNS 条最新记录）
        long removeEndIndex = size - COMPACTTRIGGERTURNS - 1;
        // 获取要压缩的历史消息（！！！核心：从 ATTENTION_SINK_TURNS 开始，跳过第 0 条锚点 !!!）
        Set<String> ttlSessions = stringRedisTemplate.opsForZSet().range(key, ATTENTION_SINK_TURNS, removeEndIndex);
        if (ttlSessions == null || ttlSessions.isEmpty()) {
            return null;
        }
        List<String> oldRoundsText = new ArrayList<>(ttlSessions);
        // 先删除再摘要（！！！核心删除保护：保留第 0 条，从 ATTENTION_SINK_TURNS 开始删除 !!!）
        stringRedisTemplate.opsForZSet().removeRange(key, ATTENTION_SINK_TURNS, removeEndIndex);
        return oldRoundsText;
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

    /**
     * 融合系统预设、历史对话上下文及摘要。
     * 并行加载 Redis 中的摘要和内存中的上下文以减少等待时间。
     *
     * @param normalizedConversationId 规范化后的会话ID
     * @return 包含完整上下文的 会话 对象
     */
    public String load(String normalizedConversationId) {
        String summaryKey = "Chat:Mem:{cid}:recent" + normalizedConversationId + "Summary";
        CompletableFuture<String> summaryFuture = CompletableFuture.supplyAsync(() -> {
            try {
                return stringRedisTemplate.opsForValue().get(summaryKey);
            } catch (Exception e) {
                log.warn("获取 summary 失败: {}", summaryKey, e);
                return null;
            }
        });
        CompletableFuture<List<String>> contextFuture = CompletableFuture.supplyAsync(() -> {
            try {
                List<String> ctx = getContext(normalizedConversationId);
                return ctx == null ? Collections.emptyList() : ctx;
            } catch (Exception e) {
                log.warn("获取 context 失败: {}", normalizedConversationId, e);
                return Collections.emptyList();
            }
        });
        // 等待两个任务完成（短时间阻塞），然后继续构建 Prompt
        return CompletableFuture.allOf(summaryFuture, contextFuture).thenApply(v->{
            String summary = summaryFuture.join();
            List<String> context = Collections.emptyList();
            return "\n\n上下文:\n" + context + "\n\n摘要:\n" + summary;
        }).join();
    }
}
