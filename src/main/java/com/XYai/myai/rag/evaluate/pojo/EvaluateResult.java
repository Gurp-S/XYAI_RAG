package com.XYai.myai.rag.evaluate.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 三层评估结果（非内部类） */
@Data
@AllArgsConstructor
public class EvaluateResult {
    private double overallF1;
    private double retrievalF1;
    private double faithfulnessF1;
    private double relevanceF1;
    private double completenessF1;
    private double ruleScore;
    private double rerankScore;
    private double llmScore;

    public static EvaluateResult skipped() {
        return new EvaluateResult(0, 0, 0, 0, 0, 0, 0, 0);
    }
}
