package com.XYai.myai.rag.etlpipeline.nodes;

import cn.hutool.core.collection.CollUtil;
import com.XYai.myai.mapper.IntentNodeMapper;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.channel.processor.BM25PostProcessor;
import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.rag.etlpipeline.pojo.NodeConfig;
import com.XYai.myai.rag.etlpipeline.pojo.NodeResult;
import com.XYai.myai.rag.intent.pojo.IntentNode;
import com.XYai.myai.redis.RedisKeyConfig;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 文档增强节点（ETL 流程中的 enricher 环节）
 * 功能：调用大模型对文档文本进行增强、摘要、结构化处理
 * 处理结果存入元数据 META_ENHANCED_TEXT
 */
@Slf4j
@Component
public class Enricher implements Ingestion {


    // 缓存：意图 code → 归一化向量 (1024 维)
    private static float[][] INTENT_VECTOR_ARRAY;  // [n][1024] 归一化向量
    private static String[] INTENT_ID_ARRAY;       // [n] 意图 nodeId
    private static volatile boolean VECTORS_LOADED = false;
    private static final Object LOAD_LOCK = new Object();

    // 相似度阈值（nodeId 是中文，可调高到 0.55）
    private static final double VECTOR_THRESHOLD = 0.35;

    /**
     * 注入 Spring AI 对话模型（大模型客户端）
     */
    @Resource
    private ChatModel chatModel;
    @Resource
    private BM25PostProcessor bm25PostProcessor;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IntentNodeMapper intentNodeMapper;
    @Resource
    private EmbeddingModel embeddingModel; // Spring AI

    /**
     * 返回当前节点类型：enricher（增强节点）
     */
    @Override
    public String getNodeType() {
        return "enricher";
    }

    /**
     * 执行增强节点的核心逻辑
     *
     * @param context 文档摄取上下文（包含 document）
     * @param config  节点配置（mode、字数限制等）
     * @return 节点执行结果
     */
    @RagTraceNode(name = "增强" ,type = "上传管道")
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        long start = System.currentTimeMillis();

        // 1. 懒加载向量
        loadIntentVectors();

        if (INTENT_VECTOR_ARRAY == null || INTENT_VECTOR_ARRAY.length == 0) {
            return NodeResult.ok("无可用意图向量");
        }

        // 2. 获取文本块
        List<Document> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.ok("没有可增强的文本");
        }

        // 3. 批量提取文本
        List<String> texts = chunks.stream()
                .map(Document::getText)
                .filter(Objects::nonNull)
                .toList();

        if (texts.isEmpty()) {
            return NodeResult.ok("无有效文本");
        }

        // 4. 批量向量化
        float[][] queryVectors = batchEmbedAndNormalize(texts);

        // 5. 并行匹配
        List<String> results = matchAllParallel(queryVectors);

        // 6. 注入
        List<Document> newChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            String intent = (i < results.size()) ? results.get(i) : "未知意图";
            newChunks.add(chunks.get(i).mutate()
                    .metadata(IngestionContext.META_INTENT_NODE, intent)
                    .build());
        }

        context.setChunks(newChunks);

        long elapsed = System.currentTimeMillis() - start;
        log.info("增强完成: {} 文本块, {} 意图, 耗时 {}ms",
                texts.size(), INTENT_ID_ARRAY.length, elapsed);

        return NodeResult.ok("增强完成");
    }

    /**
     * 懒加载意图向量（使用你的 getIntentNodes）
     */
    private void loadIntentVectors() {
        if (VECTORS_LOADED) return;
        synchronized (LOAD_LOCK) {
            if (VECTORS_LOADED) return;
            long start = System.currentTimeMillis();
            try {
                // 使用你的方法获取意图节点列表（返回 List<String>）
                List<String> nodeIdList = getIntentNodes();
                if (nodeIdList.isEmpty()) {
                    log.warn("无叶子意图节点");
                    return;
                }
                log.debug("加载 {} 个意图节点", nodeIdList.size());
                // 批量向量化 + 归一化
                float[][] vectors = batchEmbedAndNormalize(nodeIdList);
                // 转存到数组
                INTENT_ID_ARRAY = nodeIdList.toArray(new String[0]);
                INTENT_VECTOR_ARRAY = vectors;
                VECTORS_LOADED = true;
                log.info("向量加载完成: {} 个意图, 维度 {}, 耗时 {}ms",
                        INTENT_ID_ARRAY.length,
                        vectors.length > 0 ? vectors[0].length : 0,
                        System.currentTimeMillis() - start);
            } catch (Exception e) {
                log.error("向量加载失败", e);
                VECTORS_LOADED = false;
            }
        }
    }

    /**
     * 批量 Embedding + 归一化
     */
    private float[][] batchEmbedAndNormalize(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return new float[0][];
        }
        int n = texts.size();
        float[][] vectors = new float[n][];
        try {
            // 尝试批量 Embedding
            var embeddings = embeddingModel.embedForResponse(texts);
            for (int i = 0; i < n; i++) {
                vectors[i] = embeddings.getResults().get(i).getOutput();
                normalizeInPlace(vectors[i]);  // 原地归一化
            }
        } catch (Exception e) {
            // 降级：串行处理
            for (int i = 0; i < n; i++) {
                try {
                    vectors[i] = embeddingModel.embed(texts.get(i));
                    normalizeInPlace(vectors[i]);
                } catch (Exception ex) {
                    log.error("Embedding 失败: text={}", texts.get(i), ex);
                    vectors[i] = new float[1024];  // 零向量兜底
                }
            }
        }
        return vectors;
    }

    /**
     * 原地 L2 归一化（零内存分配）
     */
    private void normalizeInPlace(float[] vec) {
        if (vec == null || vec.length == 0) return;
        double norm = 0.0;
        for (float v : vec) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm < 1e-8) return;  // 防止除零
        double invNorm = 1.0 / norm;
        for (int i = 0; i < vec.length; i++) {
            vec[i] = (float) (vec[i] * invNorm);
        }
    }

    /**
     * 并行匹配所有查询向量
     */
    private List<String> matchAllParallel(float[][] queryVectors) {
        return queryVectors.length == 1
                ? Collections.singletonList(matchFast(queryVectors[0]))
                : Arrays.stream(queryVectors)
                  .parallel()  // 并行流
                  .map(this::matchFast)
                  .toList();
    }

    /**
     * 快速匹配（数组线性扫描 + 循环展开）
     */
    private String matchFast(float[] queryVec) {
        if (queryVec == null || INTENT_VECTOR_ARRAY == null) {
            return "未知意图";
        }
        int bestIdx = -1;
        double maxScore = -1.0;
        int n = INTENT_VECTOR_ARRAY.length;
        // 线性扫描（CPU 缓存友好）
        for (int i = 0; i < n; i++) {
            float[] intentVec = INTENT_VECTOR_ARRAY[i];
            if (intentVec == null) continue;
            double score = cosineSimilarityFast(queryVec, intentVec);
            if (score > maxScore) {
                maxScore = score;
                bestIdx = i;
            }
        }
        // 阈值过滤
        if (maxScore >= VECTOR_THRESHOLD && bestIdx >= 0) {
            return INTENT_ID_ARRAY[bestIdx];
        }
        return "未知意图";
    }

    /**
     * 快速余弦相似度（已归一化向量 = 点积）
     * 循环展开优化（4 倍速）
     */
    private double cosineSimilarityFast(float[] a, float[] b) {
        int len = Math.min(a.length, b.length);
        double score = 0.0;
        // 循环展开（4 倍）
        int i = 0;
        for (; i < len - 3; i += 4) {
            score += (double) a[i] * b[i]
                    + (double) a[i+1] * b[i+1]
                    + (double) a[i+2] * b[i+2]
                    + (double) a[i+3] * b[i+3];
        }
        // 处理剩余元素
        for (; i < len; i++) {
            score += (double) a[i] * b[i];
        }
        return score;  // 已归一化，无需除法
    }




    private List<String> getIntentNodes(){
        // 加载子节点
        Set<String> intentSet = stringRedisTemplate.opsForSet().members(RedisKeyConfig.intentNodeLeaveKey());
        List<String> intents = new ArrayList<>();
        if (CollUtil.isNotEmpty(intentSet)) {
            intents = new ArrayList<>(intentSet);
        }
        // Redis 为空，从数据库加载
        if (intents.isEmpty()) {
            intents = intentNodeMapper.selectList(
                            new QueryWrapper<IntentNode>().eq("children_count", 0)
                    ).stream()
                    .map(IntentNode::getNodeId)
                    .collect(Collectors.toList());
        }
        return intents;
    }
}