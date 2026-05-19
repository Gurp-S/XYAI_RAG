package com.XYai.myai.rag.aop.annotation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("node_record")
public class NodeRecord {
    /** 节点唯一 ID */
    @TableId(value = "node_id", type = IdType.INPUT)
    private String nodeId;

    /** 所属 traceId */
    private String traceId;

    /** 节点名称（可读展示） */
    private String nodeName;

    /** 节点类型（如 chat/retriever/aggregate） */
    private String nodeType;

    /** 节点耗时（毫秒） */
    private Long costTime;

    /** 节点执行状态 */
    private String status;

    /** 节点异常信息 */
    private String errorMessage;

    /** 节点开始时间（用于排序） */
    private LocalDateTime startTime;

    /** 节点结束时间 */
    @TableField("end_time")
    private LocalDateTime endTime;
}