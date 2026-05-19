package com.XYai.myai.rag.evaluate.strategy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 第一层：规则评估
 * 基于可配置的启发式规则，每个维度输出 0~1 分，最终取加权平均。
 */
@Slf4j
@Component
public class RuleEvaluator {

    // ---------- 检索维度配置 ----------
    // 达到此数量即可获得满分 1.0
    @Value("${evaluator.retrieval.full-score-chunks:5}")
    private int retrievalFullScoreChunks;

    // ---------- 回答充分性维度配置 ----------
    // 长度比低于此值视为过短
    @Value("${evaluator.fullness.too-short-ratio:0.3}")
    private double fullnessTooShortRatio;
    // 长度比高于此值视为充分
    @Value("${evaluator.fullness.enough-ratio:3.0}")
    private double fullnessEnoughRatio;

    // ---------- 负面信号维度配置 ----------
    // 负面关键词集合（逗号分隔），支持正则片段（如 "服务繁忙|系统繁忙"）
    @Value("#{'${evaluator.negative.keywords:抱歉,请稍后,服务繁忙,稍后再试,无法回答}'.split(',')}")
    private Set<String> negativeKeywords;
    // 是否使用正则匹配，若为 true 则关键词会被视为正则表达式
    @Value("${evaluator.negative.use-regex:false}")
    private boolean negativeUseRegex;

    // ---------- 延迟维度配置 ----------
    // 低于此延迟(ms)得满分
    @Value("${evaluator.latency.excellent-ms:2000}")
    private long latencyExcellentMs;
    // 高于此延迟(ms)得0分
    @Value("${evaluator.latency.poor-ms:15000}")
    private long latencyPoorMs;

    /**
     * 执行规则评估，返回带明细的结果
     */
    public EvaluationResult evaluate(String question, String answer,
                                     List<String> retrievedChunks, long latencyMs) {
        // 安全处理空值
        String q = question == null ? "" : question;
        String a = answer == null ? "" : answer;
        List<String> chunks = retrievedChunks == null ? List.of() : retrievedChunks;

        // 维度1：检索覆盖度
        double retrievalScore = evaluateRetrieval(chunks);

        // 维度2：回答充分性（基于长度比）
        double fullnessScore = evaluateFullness(q, a);

        // 维度3：负面信号
        double negativeScore = evaluateNegative(a);

        // 维度4：响应延迟
        double latencyScore = evaluateLatency(latencyMs);

        // 等权平均，可根据需要改为加权和
        double total = (retrievalScore + fullnessScore + negativeScore + latencyScore) / 4.0;
        total = clamp(total);

        log.debug("Rule evaluation - Q: [{}], A len: {}, Chunks: {}, Latency: {}ms | " +
                        "retrieval={}, fullness={}, negative={}, latency={} | final={}",
                q.length() > 50 ? q.substring(0, 50) + "..." : q,
                a.length(), chunks.size(), latencyMs,
                retrievalScore, fullnessScore, negativeScore, latencyScore, total);

        return new EvaluationResult(total, retrievalScore, fullnessScore, negativeScore, latencyScore);
    }

    // --------------------------------------------------
    // 各维度计算方法（包内可见，便于单元测试）
    // --------------------------------------------------
    double evaluateRetrieval(List<String> chunks) {
        if (chunks.isEmpty()) {
            return 0.0;   // 无检索结果 → 0分
        }
        // 数量越多越接近 1.0，但达到 fullScoreChunks 后即为 1.0
        return Math.min((double) chunks.size() / retrievalFullScoreChunks, 1.0);
    }

    double evaluateFullness(String question, String answer) {
        int qLen = Math.max(question.length(), 1);
        int aLen = answer.length();
        double ratio = (double) aLen / qLen;

        if (aLen == 0) {
            return 0.0;           // 无回答
        } else if (ratio < fullnessTooShortRatio) {
            return 0.2;           // 过短，几乎无价值
        } else if (ratio >= fullnessEnoughRatio) {
            return 1.0;           // 充分
        } else {
            // 线性插值 0.2 ~ 1.0
            return 0.2 + 0.8 * (ratio - fullnessTooShortRatio) / (fullnessEnoughRatio - fullnessTooShortRatio);
        }
    }

    double evaluateNegative(String answer) {
        if (answer.isEmpty()) {
            return 0.0;   // 无回答视为负面信号
        }
        String lower = answer.toLowerCase();
        for (String keyword : negativeKeywords) {
            String kw = keyword.trim().toLowerCase();
            if (kw.isEmpty()) continue;
            boolean matched;
            if (negativeUseRegex) {
                matched = lower.matches(".*" + kw + ".*");
            } else {
                matched = lower.contains(kw);
            }
            if (matched) {
                log.debug("Negative keyword matched: {}", kw);
                return 0.0;   // 发现负面信号 → 0 分
            }
        }
        return 1.0;   // 未发现 → 满分
    }

    double evaluateLatency(long latencyMs) {
        if (latencyMs <= latencyExcellentMs) {
            return 1.0;
        } else if (latencyMs >= latencyPoorMs) {
            return 0.0;
        } else {
            // 线性衰减 1.0 -> 0.0
            return 1.0 - (double) (latencyMs - latencyExcellentMs) / (latencyPoorMs - latencyExcellentMs);
        }
    }

    private double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    // --------------------------------------------------
    // 评估结果对象（不可变，方便监控和日志）
    // --------------------------------------------------
    public static class EvaluationResult {
        private final double totalScore;
        private final double retrievalScore;
        private final double fullnessScore;
        private final double negativeSignalScore;
        private final double latencyScore;

        public EvaluationResult(double totalScore, double retrievalScore,
                                double fullnessScore, double negativeSignalScore,
                                double latencyScore) {
            this.totalScore = totalScore;
            this.retrievalScore = retrievalScore;
            this.fullnessScore = fullnessScore;
            this.negativeSignalScore = negativeSignalScore;
            this.latencyScore = latencyScore;
        }

        public double getTotalScore() {
            return totalScore;
        }
        public double getRetrievalScore() {
            return retrievalScore;
        }
        public double getFullnessScore() {
            return fullnessScore;
        }
        public double getNegativeSignalScore() {
            return negativeSignalScore;
        }
        public double getLatencyScore() {
            return latencyScore;
        }

        @Override
        public String toString() {
            return String.format("RuleResult{total=%.3f, retrieval=%.3f, fullness=%.3f, negative=%.3f, latency=%.3f}",
                    totalScore, retrievalScore, fullnessScore, negativeSignalScore, latencyScore);
        }
    }
}