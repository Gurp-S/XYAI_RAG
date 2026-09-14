package com.XYai.myai.rag.evaluate.pojo;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 黄金集跑批单条结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xy_evaluate_golden_result")
public class GoldenResultPOJO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 跑批批次ID（雪花ID，同批次共用） */
    private Long runId;

    /** 关联用例ID */
    private Long caseId;

    /** 上下文精确率：检索结果中相关占比 */
    private Double contextPrecision;

    /** 上下文召回率：期望文档被覆盖比例 */
    private Double contextRecall;

    /** 命中率：1 至少命中一个期望文档，0 未命中 */
    private Double hitRate;

    /** 检索结果条数 */
    private Integer retrievedCount;

    /** 检索耗时 ms */
    private Long latencyMs;

    /** 是否通过门控阈值：1 通过，0 不达标 */
    private Integer passed;

    /** 明细 JSON：检索到的 doc_id 列表与匹配情况 */
    private String detailJson;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
