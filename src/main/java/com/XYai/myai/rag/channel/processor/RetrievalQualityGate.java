package com.XYai.myai.rag.channel.processor;

import com.XYai.myai.rag.channel.MultiChannelRetrievalEngine;
import com.XYai.myai.rag.channel.pojo.RetrievalProperties;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 检索质量门控（CRAG 式纠错环）。
 * <p>
 * 流程：rerank 融合分最高值低于阈值 → 判定首轮证据不足 →
 * 用「原始问题 + 改写问题」组合重新检索一次（不额外调 LLM）→
 * 取两轮中最高分更高的一组；仍低于阈值则标记证据不足（由生成侧拒答）。
 * </p>
 */
@Slf4j
@Component
public class RetrievalQualityGate {

    @Resource
    private MultiChannelRetrievalEngine retrievalEngine;
    @Resource
    private RetrievalProperties retrievalProperties;

    @Data
    @AllArgsConstructor
    public static class GateOutcome {
        private List<RetrievedChunk> chunks;
        private boolean insufficientEvidence;
        private double bestScore;
        private boolean retried;
    }

    public GateOutcome gate(List<RetrievedChunk> retrieved,
                            Map<String, Integer> entityFileChunkIds,
                            RewriteResult rewritten,
                            Long conversationId,
                            String originalMessage) {
        boolean retryEnabled = retrievalProperties.isQualityGateRetryEnabled();
        double threshold = retrievalProperties.getQualityGateThreshold();
        double firstEvidence = evidenceScore(retrieved);

        if (!retryEnabled || firstEvidence >= threshold) {
            return new GateOutcome(retrieved, false, firstEvidence, false);
        }

        // 首轮证据不足：用原始问题为主查询、改写问题为子问题重检索一次
        log.info("检索质量门控触发: evidence={} < threshold={}, 执行一次重检索", firstEvidence, threshold);
        RewriteResult retryQuery = RewriteResult.builder()
                .rewrittenQuery(originalMessage)
                .subQuery(rewritten != null && rewritten.getRewrittenQuery() != null
                        ? List.of(rewritten.getRewrittenQuery())
                        : null)
                .build();
        List<RetrievedChunk> retryResults = retrievalEngine.retrieve(
                entityFileChunkIds, retryQuery, conversationId, originalMessage);
        double retryEvidence = evidenceScore(retryResults);

        List<RetrievedChunk> better = retryEvidence > firstEvidence ? retryResults : retrieved;
        double best = Math.max(firstEvidence, retryEvidence);
        boolean insufficient = best < threshold;
        if (insufficient) {
            log.info("重检索后证据仍不足: evidence={} < threshold={}", best, threshold);
        }
        return new GateOutcome(better, insufficient, best, true);
    }

    /**
     * 证据分 = top分 × 支撑度系数。
     * 支撑度系数 = 0.7 + 0.3 × min(1, 支撑块数/目标数)，
     * 支撑块 = 融合分 ≥ supportFloor 的候选。单块侥幸高分最多拿到 0.7×top，
     * 多块支撑（≥target 块 ≥floor）拿到完整 top 分——证据既要有高度也要有广度。
     */
    private double evidenceScore(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return 0.0;
        double top = 0.0;
        int support = 0;
        double floor = retrievalProperties.getQualityGateSupportFloor();
        for (RetrievedChunk c : chunks) {
            if (c == null || c.getScore() == null) continue;
            double s = c.getScore();
            if (s > top) top = s;
            if (s >= floor) support++;
        }
        double supportFactor = 0.7 + 0.3 * Math.min(1.0,
                (double) support / Math.max(1, retrievalProperties.getQualityGateSupportTarget()));
        return top * supportFactor;
    }
}
