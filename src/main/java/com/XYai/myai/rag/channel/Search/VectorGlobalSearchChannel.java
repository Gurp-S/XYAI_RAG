package com.XYai.myai.rag.channel.Search;

import com.XYai.myai.rag.milvus.MilvusService;
import com.XYai.myai.rag.milvus.MilvusCollectionService;
import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.channel.POJO.SearchChannel;
import com.XYai.myai.rag.channel.POJO.SearchChannelResult;
import com.XYai.myai.rag.channel.POJO.SearchContext;
import com.XYai.myai.rag.intent.POJO.NodeScore;
import com.XYai.myai.rag.intent.POJO.SubQuestionIntent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * 全局 Milvus 向量检索通道
 * <p>
 * 功能：当用户问题**没有明确意图**或**意图置信度较低**时，
 * 对系统中**所有 Milvus 集合**执行并行向量相似度检索，返回全局最相关的文本片段
 * 属于兜底检索策略，保证无意图时也能返回相关知识
 */
@Slf4j
@Component
public class VectorGlobalSearchChannel implements SearchChannel {

    /**
     * 注入 Milvus 工具服务：用于查询所有集合、判断集合是否存在
     */
    @Resource
    private MilvusService milvusService;

    /**
     * 注入集合动态路由的向量存储服务：根据 collectionName 获取对应的 VectorStore
     */
    @Resource
    private MilvusCollectionService milvusCollectionService;

    /**
     * 向量相似度阈值：低于该分数的结果会被过滤
     */
    private static final double SIMILARITY_THRESHOLD = 0.7;

    /**
     * 每个集合默认返回的 TopK 最相似文档数
     */
    private static final int DEFAULT_TOP_K = 5;

    /**
     * 意图置信度阈值：低于该值，触发全局向量检索
     */
    private static final double CONFIDENCE_THRESHOLD = 0.7;

    /**
     * 通道名称：用于标识和日志追踪
     */
    @Override
    public String getName() {
        return "vector-global-search";
    }

    /**
     * 执行优先级：数值越大优先级越低
     * 这里设为10，确保在意图定向检索之后执行（兜底策略）
     */
    @Override
    public int getPriority() {
        return 10;
    }

    /**
     * 通道类型标识
     */
    @Override
    public String getType() {
        return "vector-global-search";
    }

    /**
     * 判断当前通道是否启用（是否执行全局检索）
     * 启用条件：
     * 1. 上下文没有识别到任何知识库意图
     * 2. 所有意图的最高置信度 < 阈值（意图不可信，走全局检索）
     */
    @Override
    public boolean isEnabled(SearchContext context) {
        // 无意图 → 直接启用
        if (context.getKbIntents().isEmpty()) {
            return true;
        }

        // 计算所有意图节点中的最高置信度
        double maxScore = context.getKbIntents().stream()
                .map(SubQuestionIntent::getNodeScore)
                .flatMap(nodesScore -> nodesScore.getNodeScoreList().stream())
                .mapToDouble(NodeScore::getScore)
                .max().orElse(0.0);

        // 最高置信度 ≤ 0.4 或 0.4 < 置信度 < 阈值 → 启用全局检索
        return !(maxScore > 0.4) && maxScore < CONFIDENCE_THRESHOLD;
    }

    /**
     * 执行全局向量检索
     * 流程：
     * 1. 获取系统所有 Milvus 集合
     * 2. 并行对每个集合执行向量检索
     * 3. 合并所有结果 → 按分数降序排序 → 取TopK → 返回
     */
    @Override
    public SearchChannelResult search(SearchContext context) {
        // 1. 获取系统中所有 Milvus 集合名称
        List<String> allCollectionNames = milvusService.getAllCollectionNames();

        // 2. 并行检索所有集合 → 合并结果 → 排序 → 截断
        List<RetrievedChunk> allChunks = allCollectionNames.stream()
                .map(collectionName -> CompletableFuture.supplyAsync(
                        () -> getChunks(collectionName, context),  // 异步检索单个集合
                        Executors.newFixedThreadPool(10)         // 线程池并行执行
                ))
                .map(CompletableFuture::join)  // 等待所有异步任务完成
                .flatMap(List::stream)         // 展平所有集合的检索结果
                .sorted((c1, c2) -> Double.compare(c2.getScore(), c1.getScore())) // 按分数降序
                .limit(DEFAULT_TOP_K)          // 取全局Top5
                .toList();

        // 3. 封装并返回检索结果
        return SearchChannelResult.builder()
                .channelName(getName())
                .chunks(allChunks)
                .metadata(allChunks.stream().map(RetrievedChunk::getMetadata).toList())
                .build();
    }

    /**
     * 对单个 Milvus 集合执行向量检索，并转换为 RetrievedChunk 格式
     * 包含：集合合法性校验、异常捕获、空值保护
     */
    private List<RetrievedChunk> getChunks(String collectionName, SearchContext context) {
        // 集合名校验：空集合直接跳过
        if (collectionName == null || collectionName.isBlank()) {
            log.debug("collectionName is blank, skip");
            return List.of();
        }

        try {
            // 显式 load 后再查
            VectorStore vectorStore = milvusCollectionService.ensureReadyForRead(collectionName);
            if (vectorStore == null) {
                log.info("集合未加载:{}",collectionName);
                return List.of();
            }
            // 执行相似度检索
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(context.getQuestion())       // 用户问题
                    .topK(DEFAULT_TOP_K)                // 每个集合返回Top5
                    .similarityThreshold(SIMILARITY_THRESHOLD) // 分数过滤
                    .build());

            if (documents == null || documents.isEmpty()) {
                return List.of();
            }

            // 把 Document 转为业务统一的 RetrievedChunk
            List<RetrievedChunk> chunks = new ArrayList<>();
            for (Document document : documents) {
                if (document == null) continue;

                Map<String, Object> metadata = new HashMap<>(document.getMetadata());
                Double score = extractScore(metadata);

                // 分数兜底
                if (score == null) {
                    score = 0.0;
                    metadata.putIfAbsent("score", score);
                }

                chunks.add(RetrievedChunk.builder()
                        .content(document.getText())
                        .metadata(metadata)
                        .score(score)
                        .build());
            }
            return chunks;

        } catch (Exception e) {
            // 异常安全：单个集合查询失败不影响全局，只打印日志并跳过
            log.error("匹配失败 Milvus 集合 '{}': {}", collectionName, e.getMessage(), e);
            return List.of();
        }
    }

    /**
     * 从元数据中提取相似度分数
     * 兼容两种字段：
     * 1. score：直接相似度
     * 2. distance：距离值（越小越相似），自动转成 1-distance 作为相似度
     */
    private Double extractScore(Map<String, Object> metadata) {
        if (metadata == null) return null;

        // 优先取 score
        Object scoreObj = metadata.get("score");
        if (scoreObj instanceof Number n) {
            return n.doubleValue();
        }

        // 兼容 Milvus 原生 distance 字段
        Object distanceObj = metadata.get("distance");
        if (distanceObj instanceof Number n) {
            return 1.0 - n.doubleValue();
        }

        return null;
    }
}