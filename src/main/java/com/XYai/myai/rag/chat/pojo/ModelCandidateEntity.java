package com.XYai.myai.rag.chat.pojo;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("xy_model_candidate")
public class ModelCandidateEntity {

    /** 模型唯一标识 */
    @TableId(type = IdType.INPUT)
    private String name;


    /** 模型显示名称 */
    private String displayName;

    /** API实际模型名 */
    private String apiModel;

    /** 优先级（1最高） */
    private int priority = 5;

    /** 是否启用 */
    private boolean enabled = true;

    /** 权重 */
    private int weight = 1;

    /** 温度 */
    private double temperature = 0.3;

    /** 最大 Token */
    private int maxTokens = 2000;

    /** 模型用途说明（如：通用对话、代码生成、翻译） */
    private String purpose;

    // 熔断配置（展平存储）
    private int failureThreshold = 50;
    private long waitDurationOpen = 10000L;
    private int slidingWindowSize = 10;
    private int minimumCalls = 5;

    // 创建/更新时间（自动填充，可选）
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}