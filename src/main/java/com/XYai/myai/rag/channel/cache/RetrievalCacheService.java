package com.XYai.myai.rag.channel.cache;

import com.alibaba.fastjson2.JSON;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 检索多层缓存（全部带 userId 权限维度，杜绝越权命中）。
 * <ul>
 *   <li>L1 查询 embedding 缓存：Redis，TTL 7 天，跨实例共享</li>
 *   <li>L2 检索结果缓存：归一化 query + userId → chunk 列表，Redis TTL 10 分钟</li>
 *   <li>L3 语义检索缓存：本机 Caffeine 存最近查询向量与结果，余弦 ≥ 阈值直接复用（单实例部署语义足够）</li>
 *   <li>L4 答案缓存：归一化 query + userId + 检索指纹 → 完整回答，Redis TTL 1 小时，命中回放模拟流式</li>
 * </ul>
 */
@Slf4j
@Service
public class RetrievalCacheService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    @Qualifier("defaultCache")
    private Cache<String, Object> localCache;

    @Value("${retrieval.cache.embedding-ttl-hours:168}")
    private int embeddingTtlHours;
    @Value("${retrieval.cache.retrieval-ttl-minutes:10}")
    private int retrievalTtlMinutes;
    @Value("${retrieval.cache.answer-ttl-minutes:60}")
    private int answerTtlMinutes;
    @Value("${retrieval.cache.semantic-enabled:true}")
    private boolean semanticEnabled;
    @Value("${retrieval.cache.semantic-threshold:0.93}")
    private double semanticThreshold;
    @Value("${retrieval.cache.semantic-max-entries:500}")
    private int semanticMaxEntries;

    // ==================== L1 embedding 缓存 ====================

    /** L0 进程内 embedding 缓存：高并发下省去每查询的 Redis 往返（Redis 仍为跨实例共享层） */
    private final com.github.benmanes.caffeine.cache.Cache<String, float[]> localEmbeddingCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(20_000)
                    .expireAfterWrite(java.time.Duration.ofMinutes(30))
                    .build();

    public float[] getEmbedding(String text) {
        if (text == null || text.isBlank()) return null;
        String key = sha256(text);
        float[] local = localEmbeddingCache.getIfPresent(key);
        if (local != null) return local;
        try {
            String cached = stringRedisTemplate.opsForValue().get(embeddingKey(text));
            if (cached != null) {
                float[] v = decodeVector(cached);
                localEmbeddingCache.put(key, v);
                return v;
            }
        } catch (Exception e) {
            log.debug("embedding 缓存读取失败: {}", e.getMessage());
        }
        return null;
    }

    public void putEmbedding(String text, float[] vector) {
        if (vector == null || text == null || text.isBlank()) return;
        try {
            localEmbeddingCache.put(sha256(text), vector);
            stringRedisTemplate.opsForValue().set(embeddingKey(text), encodeVector(vector),
                    Duration.ofHours(embeddingTtlHours));
        } catch (Exception e) {
            log.debug("embedding 缓存写入失败: {}", e.getMessage());
        }
    }

    private String embeddingKey(String text) {
        return "xyai:cache:emb:" + sha256(text);
    }

    // ==================== L2 检索结果缓存 ====================

    public List<RetrievedChunk> getRetrieval(String normalizedQuery, Long userId) {
        try {
            String cached = stringRedisTemplate.opsForValue().get(retrievalKey(normalizedQuery, userId));
            if (cached == null) return null;
            return JSON.parseArray(cached, RetrievedChunk.class);
        } catch (Exception e) {
            log.debug("检索缓存读取失败: {}", e.getMessage());
            return null;
        }
    }

    public void putRetrieval(String normalizedQuery, Long userId, List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return; // 空结果不缓存，避免污染
        try {
            stringRedisTemplate.opsForValue().set(retrievalKey(normalizedQuery, userId),
                    JSON.toJSONString(chunks), Duration.ofMinutes(retrievalTtlMinutes));
        } catch (Exception e) {
            log.debug("检索缓存写入失败: {}", e.getMessage());
        }
    }

    private String retrievalKey(String normalizedQuery, Long userId) {
        return "xyai:cache:rt:" + userId + ":" + sha256(normalizedQuery);
    }

    // ==================== L3 语义检索缓存（本机） ====================

    private record SemanticEntry(float[] vector, Long userId, List<RetrievedChunk> chunks) {}

    private final List<SemanticEntry> semanticStore = new CopyOnWriteArrayList<>();

    public List<RetrievedChunk> getSemanticRetrieval(float[] queryVector, Long userId) {
        if (!semanticEnabled || queryVector == null) return null;
        for (SemanticEntry entry : semanticStore) {
            if (!entry.userId().equals(userId)) continue; // 权限隔离
            if (cosine(queryVector, entry.vector()) >= semanticThreshold) {
                return entry.chunks();
            }
        }
        return null;
    }

    public void putSemanticRetrieval(float[] queryVector, Long userId, List<RetrievedChunk> chunks) {
        if (!semanticEnabled || queryVector == null || chunks == null || chunks.isEmpty()) return;
        semanticStore.add(new SemanticEntry(queryVector, userId, chunks));
        while (semanticStore.size() > semanticMaxEntries) {
            semanticStore.remove(0);
        }
    }

    // ==================== L4 答案缓存 ====================

    public String getAnswer(String answerCacheKey) {
        if (answerCacheKey == null) return null;
        try {
            return stringRedisTemplate.opsForValue().get("xyai:cache:ans:" + answerCacheKey);
        } catch (Exception e) {
            return null;
        }
    }

    public void putAnswer(String answerCacheKey, String answer) {
        if (answerCacheKey == null || answer == null || answer.isBlank()) return;
        try {
            stringRedisTemplate.opsForValue().set("xyai:cache:ans:" + answerCacheKey, answer,
                    Duration.ofMinutes(answerTtlMinutes));
        } catch (Exception e) {
            log.debug("答案缓存写入失败: {}", e.getMessage());
        }
    }

    /** 答案缓存 key：归一化问题 + 用户 + 检索结果指纹（检索变了答案缓存自动失效） */
    public String buildAnswerKey(String normalizedQuery, Long userId, List<RetrievedChunk> retrieved) {
        List<String> ids = new ArrayList<>();
        if (retrieved != null) {
            for (RetrievedChunk c : retrieved) {
                if (c != null && c.getId() != null) ids.add(c.getId());
            }
        }
        Collections.sort(ids);
        return sha256(normalizedQuery + "|" + userId + "|" + String.join(",", ids));
    }

    // ==================== 工具 ====================

    /** 归一化：小写 + 折叠空白（保留语义的轻量归一化，不做分词级归一避免误命中） */
    public static String normalizeQuery(String query) {
        if (query == null) return "";
        return query.strip().toLowerCase().replaceAll("\\s+", " ");
    }

    private static float[] decodeVector(String base64) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        float[] vector = new float[bytes.length / 4];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }

    private static String encodeVector(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0.0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(text.hashCode());
        }
    }
}
