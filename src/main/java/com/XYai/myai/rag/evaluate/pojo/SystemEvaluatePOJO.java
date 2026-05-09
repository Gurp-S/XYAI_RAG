package com.XYai.myai.rag.evaluate.pojo;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
@TableName("xy_system_evaluate")
public class SystemEvaluatePOJO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String conversationId;
    private String messageId;
    private Long userId;

    private Double overallScore;

    // RAG 分维度指标
    private Double retrievalScore;
    private Double faithfulnessScore;
    private Double answerRelevanceScore;
    private Double completenessScore;

    private Integer retrievedDocCount;
    private Long latencyMs;
    private String modelName;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}