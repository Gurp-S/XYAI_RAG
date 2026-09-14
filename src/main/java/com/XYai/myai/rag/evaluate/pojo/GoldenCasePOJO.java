package com.XYai.myai.rag.evaluate.pojo;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 黄金问答集用例（RAGAS 风格评估基准）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("xy_evaluate_golden_case")
public class GoldenCasePOJO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 用户问题 */
    private String question;

    /** 标准答案（人工标注或由 badcase 修正后填入） */
    private String groundTruth;

    /** 期望命中的文档/分块标识，JSON 数组字符串，元素为 fileId 或 fileId:chunkId */
    private String expectedDocIds;

    /** 用例来源：MANUAL-人工标注 / BADCASE-点踩回流 */
    private String source;

    /** 是否启用：1 启用，0 待审 */
    private Integer enabled;

    /** 备注（badcase 回流时记录原始回答） */
    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
