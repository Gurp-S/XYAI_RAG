package com.XYai.myai.rag.evaluate.strategy;

import com.XYai.myai.commonUtils.IK.IKAnalyzerTokenize;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 第二层：Rerank 评估
 * 基于检索内容与答案的 token 重叠度计算忠实度分数
 */
@Slf4j
@Component
public class RerankEvaluator {

    @Resource
    private IKAnalyzerTokenize tokenizer;

    public double evaluate(String question, String answer, List<String> retrievedChunks) {
        if (answer == null || answer.isBlank())
            return 0;
        if (retrievedChunks == null || retrievedChunks.isEmpty()) {
            // 无检索内容时评估回答自身的质量
            return evaluateSelfConsistency(answer);
        }

        // 对每个检索块计算与答案的 token 重叠（Jaccard）
        Set<String> answerTokens = new HashSet<>(tokenizer.tokenize(answer));

        double bestOverlap = 0;
        for (String chunk : retrievedChunks) {
            if (chunk == null || chunk.isBlank())
                continue;
            Set<String> chunkTokens = new HashSet<>(tokenizer.tokenize(chunk));
            if (chunkTokens.isEmpty())
                continue;

            // Jaccard = intersection / union
            Set<String> intersection = new HashSet<>(answerTokens);
            intersection.retainAll(chunkTokens);

            Set<String> union = new HashSet<>(answerTokens);
            union.addAll(chunkTokens);

            double jaccard = (double) intersection.size() / union.size();
            bestOverlap = Math.max(bestOverlap, jaccard);
        }

        // 如果有多个检索块，对覆盖度加分
        double coverageBonus = 0;
        if (retrievedChunks.size() >= 3) {
            Set<String> allChunkTokens = new HashSet<>();
            for (String chunk : retrievedChunks) {
                allChunkTokens.addAll(tokenizer.tokenize(chunk));
            }
            Set<String> inter = new HashSet<>(answerTokens);
            inter.retainAll(allChunkTokens);
            coverageBonus = Math.min((double) inter.size() / Math.max(answerTokens.size(), 1), 0.15);
        }

        return Math.min(bestOverlap + coverageBonus, 1.0);
    }

    /** 无检索时：检查答案是否有实质内容 */
    private double evaluateSelfConsistency(String answer) {
        List<String> tokens = tokenizer.tokenize(answer);
        if (tokens.size() < 5)
            return 0.2;
        if (tokens.size() > 50)
            return 0.6;
        return 0.4;
    }
}
