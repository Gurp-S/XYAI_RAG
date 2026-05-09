package com.XYai.myai.rag.channel.processor;

import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 检索结果重排序 (Rerank) 后处理器。
 */
@Slf4j
@Component
public class RerankPostProcessor implements SearchResultPostProcessor{

    @Override
    public String getName() {
        return "";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        return List.of();
    }
}
//public class RerankPostProcessor implements SearchResultPostProcessor {
//
//    private static final String NAME = "rerank-processor";
//    private static final double WEIGHT_RETRIEVAL = 0.2;
//    private static final double WEIGHT_RERANK = 0.6;
//    private static final double WEIGHT_BM25 = 0.2;
//
//    //@Resource
//    //private LLMService llmService;
//
//    @Override
//    public String getName() {
//        return NAME;
//    }
//
//    @Override
//    public int getOrder() {
//        return 10;
//    }
//
//    @Override
//    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
//        // 输入校验
//        if (chunks == null || chunks.isEmpty()) {
//            log.debug("[Rerank-Skip] empty input");
//            return List.of();
//        }
//
//        // 构建输入
//        String query = Optional.ofNullable(context).map(SearchContext::getOriginalQuery).orElse("");
//        List<String> contents = chunks.stream()
//                .filter(Objects::nonNull)
//                .map(RetrievedChunk::getContent)
//                .filter(Objects::nonNull)
//                .toList();
//
//        if (contents.isEmpty()) {
//            log.warn("文本为空");
//            return fallbackByBm25(chunks, context);
//        }
//
//        // 调用 rerank
//        List<Map<String, Object>> rerankResults = null;
//        try {
//            rerankResults = null;//llmService.rerank(query, contents);
//            log.debug("重拍结果: {}",
//                    rerankResults != null ? "success (" + rerankResults.size() + ")" : "null");
//        } catch (Exception e) {
//            log.warn("重排失败", e);
//        }
//
//        // 降级策略
//        if (rerankResults == null || rerankResults.isEmpty()) {
//            log.debug("重排为空,使用bm25排序");
//            return fallbackByBm25(chunks, context);
//        }
//
//        // 构建分数映射
//        Map<Integer, Double> chunkScores = new HashMap<>();
//        for (Map<String, Object> result : rerankResults) {
//            if (result == null) continue;
//
//            Object idxObj = result.get("index");
//            Object scoreObj = result.get("score");
//
//            if (idxObj instanceof Integer index && scoreObj instanceof Number scoreNum) {
//                chunkScores.put(index, scoreNum.doubleValue());
//            }
//        }
//
//        // 分数融合
//        for (int i = 0; i < chunks.size(); i++) {
//            RetrievedChunk chunk = chunks.get(i);
//            if (chunk == null) continue;
//
//            // 所有分数
//            Double retrievalScore = Optional.ofNullable(chunk.getScore()).orElse(0.0);
//            Double bm25Score = Optional.ofNullable(chunk.getBm25Score()).orElse(0.0);
//            Double rerankScore = Optional.ofNullable(chunkScores.get(i)).orElse(0.0);
//
//            // 计算最终分数
//            double finalScore = WEIGHT_RETRIEVAL * retrievalScore +
//                    WEIGHT_RERANK * rerankScore +
//                    WEIGHT_BM25 * bm25Score;
//
//            chunk.setScore(clamp(finalScore));
//            log.debug("重排后分数: idx={}, retrieval={}, bm25={}, rerank={}, final={}",
//                    i, retrievalScore, bm25Score, rerankScore, chunk.getScore());
//        }
//
//        // 排序返回
//        Integer topK = Optional.ofNullable(context).map(SearchContext::getTopK).orElse(null);
//
//        return chunks.stream()
//                .filter(Objects::nonNull)
//                .sorted(Comparator.comparing(
//                        chunk -> Optional.ofNullable(chunk.getScore()).orElse(0.0),
//                        Comparator.reverseOrder()
//                ))
//                .limit(topK != null ? topK : chunks.size())
//                .collect(Collectors.toList());
//    }
//
//    /**
//     * 降级策略 按 BM25 分数排序
//     */
//    private List<RetrievedChunk> fallbackByBm25(List<RetrievedChunk> chunks, SearchContext context) {
//        Integer topK = Optional.ofNullable(context).map(SearchContext::getTopK).orElse(null);
//        return chunks.stream()
//                .filter(Objects::nonNull)
//                .sorted(Comparator.comparing(
//                        chunk -> Optional.ofNullable(chunk.getBm25Score()).orElse(0.0),
//                        Comparator.reverseOrder()
//                ))
//                .limit(topK != null ? topK : chunks.size())
//                .collect(Collectors.toList());
//    }
//
//    /**
//     * 确保分数在 0-1 范围
//     */
//    private static double clamp(Double v) {
//        if (v == null || v.isNaN() || v.isInfinite()) return 0.0;
//        return Math.clamp(v, 0.0, 1.0);
//    }
//}