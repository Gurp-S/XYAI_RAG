package com.XYai.myai.rag.channel.processor;

import com.XYai.myai.commonUtils.OllamaRerankerService;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.channel.pojo.RetrievalProperties;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RerankPostProcessor implements SearchResultPostProcessor {

    private static final String NAME = "rerank-processor";
    private static final double WEIGHT_RETRIEVAL = 0.2;
    private static final double WEIGHT_RERANK = 0.6;
    private static final double WEIGHT_BM25 = 0.2;

    // 用于降级混合排序的权重（可调整）
    private static final double FALLBACK_RETRIEVAL_WEIGHT = 0.7;
    private static final double FALLBACK_BM25_WEIGHT = 0.3;

    @Resource
    private OllamaRerankerService rerankerService;
    @Resource
    private RetrievalProperties retrievalProperties;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    @RagTraceNode(name = "rerank", type = "process" ,taskIdArg = "processRoot")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        String query = Optional.ofNullable(context)
                .map(SearchContext::getOriginalQuery)
                .orElse("");

        List<String> contents = chunks.stream()
                .filter(Objects::nonNull)
                .map(RetrievedChunk::getContent)
                .filter(Objects::nonNull)
                .toList();

        if (contents.isEmpty()) {
            log.warn("文本为空，降级为混合排序");
            return fallbackWithHybridScore(chunks, context);
        }

        List<Double> rerankScores = null;
        if(retrievalProperties.getRerankLLM()){
            try {
                rerankScores = rerankerService.rerank(query, contents);
                log.debug("Rerank 完成，获得 {} 个分数", rerankScores != null ? rerankScores.size() : 0);
            } catch (Exception e) {
                log.warn("Rerank 调用失败，降级为混合排序", e);
            }
        }

        if (rerankScores == null || rerankScores.size() != chunks.size()) {
            log.debug("Rerank 未开启或者失败,启用混合排序");
            return fallbackWithHybridScore(chunks, context);
        }

        // 正常 Rerank 融合
        Map<Integer, Double> chunkScores = new HashMap<>();
        for (int i = 0; i < rerankScores.size(); i++) {
            chunkScores.put(i, rerankScores.get(i));
        }

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            if (chunk == null) continue;

            double retrievalScore = Optional.ofNullable(chunk.getScore()).orElse(0.0);
            double bm25Score = Optional.ofNullable(chunk.getBm25Score()).orElse(0.0);
            double rerankScore = Optional.ofNullable(chunkScores.get(i)).orElse(0.0);

            double finalScore = WEIGHT_RETRIEVAL * retrievalScore +
                    WEIGHT_RERANK * rerankScore +
                    WEIGHT_BM25 * bm25Score;

            chunk.setScore(clamp(finalScore));
            log.debug("Rerank idx={}: retrieval={}, bm25={}, rerank={}, final={}",
                    i, retrievalScore, bm25Score, rerankScore, chunk.getScore());
        }

        Integer topK = Optional.ofNullable(context).map(SearchContext::getTopK).orElse(null);
        return sortAndLimit(chunks, topK);
    }

    /**
     * 降级混合排序：融合向量分数和 BM25 分数（无需 Rerank）
     */
    private List<RetrievedChunk> fallbackWithHybridScore(List<RetrievedChunk> chunks, SearchContext context) {
        log.debug("使用混合分数降级排序 (向量:{} / BM25:{})", FALLBACK_RETRIEVAL_WEIGHT, FALLBACK_BM25_WEIGHT);
        for (RetrievedChunk chunk : Objects.requireNonNullElse(chunks, Collections.<RetrievedChunk>emptyList())) {
            if (chunk != null) {
                double retrievalScore = Optional.ofNullable(chunk.getScore()).orElse(0.0);
                double bm25Score = Optional.ofNullable(chunk.getBm25Score()).orElse(0.0);
                double hybridScore = FALLBACK_RETRIEVAL_WEIGHT * retrievalScore +
                        FALLBACK_BM25_WEIGHT * bm25Score;
                chunk.setScore(clamp(hybridScore));
            }
        }
        Integer topK = Optional.ofNullable(context).map(SearchContext::getTopK).orElse(null);
        return sortAndLimit(chunks, topK);
    }

    private List<RetrievedChunk> sortAndLimit(List<RetrievedChunk> chunks, Integer topK) {
        if (chunks == null) return List.of();
        return chunks.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(
                        chunk -> Optional.ofNullable(chunk.getScore()).orElse(0.0),
                        Comparator.reverseOrder()
                ))
                .limit(topK != null ? topK : chunks.size())
                .collect(Collectors.toList());
    }

    private static double clamp(Double v) {
        if (v == null || v.isNaN() || v.isInfinite()) return 0.0;
        return Math.clamp(v, 0.0, 1.0);
    }
}