package com.XYai.myai.rag.evaluate.pojo;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xy_system_evaluate")
public class SystemEvaluatePOJO {
    @TableId(type = IdType.INPUT)
    private Long chatMessageId;

    private Long conversationId;
    private Long userId;

    /** 综合 F1 */
    private Double overallScore;

    /** 各维度 F1 */
    private Double retrievalScore;
    private Double faithfulnessScore;
    private Double answerRelevanceScore;
    private Double completenessScore;

    /** 三层原始分 */
    private Double ruleScore;
    private Double rerankScore;
    private Double llmScore;

    private Integer retrievedDocCount;
    private Long latencyMs;
    private String modelName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 扩展 JSON */
    private String extraJson;
}
