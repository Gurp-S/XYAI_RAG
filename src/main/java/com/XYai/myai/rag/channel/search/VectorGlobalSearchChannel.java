package com.XYai.myai.rag.channel.search;

import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchChannel;
import com.XYai.myai.rag.channel.pojo.SearchChannelResult;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import com.XYai.myai.rag.intent.pojo.NodeScore;
import com.XYai.myai.rag.intent.pojo.SubQuestionIntent;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Component
public class VectorGlobalSearchChannel implements SearchChannel {

    private static final double SIMILARITY_THRESHOLD = 0.4;
    private static final int DEFAULT_TOP_K = 5;
    private static final double CONFIDENCE_THRESHOLD = 0.6;

    @Resource
    private VectorStore vectorStore;

    @Resource(name = "searchChannelExecutor")
    private ThreadPoolTaskExecutor searchChannelExecutor;

    @Override
    public String getName() {
        return "vector-global-search";
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public String getType() {
        return "vector-global-search";
    }

    /**
     * 启用判断：无意图 或 平均置信度过低
     */
    @Override
    public boolean isEnabled(SearchContext context) {
        // 无意图 → 直接启用
//        if (context.getKbIntents().isEmpty()) {
//            return true;
//        }
        // 计算所有意图节点中的最高置信度
//        double maxScore = context.getKbIntents().stream()
//                .map(SubQuestionIntent::getNodeScore)
//                .flatMap(nodesScore -> nodesScore.getNodeScoreList().stream())
//                .mapToDouble(NodeScore::getScore)
//                .max().orElse(0.0);
//        // 最高置信度 < CONFIDENCE_THRESHOLD -> 启用全局检索
//        return maxScore < CONFIDENCE_THRESHOLD;
        return true;
    }

    /**
     * 核心检索：只搜单个集合 + 正确并行 + 空安全
     */
    @Override
    public SearchChannelResult search(SearchContext context) {
        // 空安全：优先使用 rewriteQuestion，如果为空回退到 question（兼容不同构造方式）
        RewriteResult rewriteResult = Optional.ofNullable(context.getRewriteQuestion())
                .orElse(RewriteResult.builder().rewrittenQuery(context.getOriginalQuery()).subQuery(null).build());
        if (rewriteResult == null) {
            return SearchChannelResult.builder()
                    .channelName(getName())
                    .chunks(Collections.emptyList())
                    .metadata(Collections.emptyList())
                    .build();
        }

        // 获取查询列表
        List<String> queryList = Optional.ofNullable(rewriteResult.getSubQuery())
                .filter(list -> !list.isEmpty())
                .orElse(List.of(rewriteResult.getRewrittenQuery()));

        // 并行检索
        List<CompletableFuture<List<RetrievedChunk>>> futures = queryList.stream()
                .map(query -> CompletableFuture.supplyAsync(
                        () -> getChunks(query),
                        searchChannelExecutor
                ))
                .toList();

        // 合并结果 → 排序 → 截断
        List<RetrievedChunk> vectorSearchResult = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(DEFAULT_TOP_K)
                .collect(Collectors.toList());

        return SearchChannelResult.builder()
                .channelName(getName())
                .chunks(vectorSearchResult)
                .metadata(vectorSearchResult.stream().map(RetrievedChunk::getMetadata).toList())
                .build();
    }

    /**
     * 集合向量检索
     */
    private List<RetrievedChunk> getChunks(String query) {
        try {
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(DEFAULT_TOP_K)
                    .similarityThreshold(SIMILARITY_THRESHOLD)
                    .build());

            if (documents == null || documents.isEmpty()) {
                return List.of();
            }

            List<RetrievedChunk> chunks = new ArrayList<>();
            for (Document doc : documents) {
                if (doc == null) continue;

                Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
                Double score = extractScore(metadata);
                // 兜底分数 ≥ 阈值（逻辑不自相矛盾）
                if (score == null) {
                    score = SIMILARITY_THRESHOLD;
                    metadata.put("score", score);
                }

                chunks.add(RetrievedChunk.builder()
                        .content(doc.getText())
                        .metadata(metadata)
                        .score(score)
                        .build());
            }
            return chunks;

        } catch (Exception e) {
            log.error("向量检索失败", e);
            return List.of();
        }
    }

    /**
     * 提取 score / distance
     */
    private Double extractScore(Map<String, Object> metadata) {
        if (metadata == null) return null;

        Object scoreObj = metadata.get("score");
        if (scoreObj instanceof Number n) {
            return n.doubleValue();
        }

        Object distObj = metadata.get("distance");
        if (distObj instanceof Number n) {
            return 1.0 - n.doubleValue();
        }

        return null;
    }
}