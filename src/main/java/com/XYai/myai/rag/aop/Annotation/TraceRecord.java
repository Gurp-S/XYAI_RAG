package com.XYai.myai.rag.aop.Annotation;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

/**
 * 链路追踪记录实体类。
 * 映射数据库 `trace_record` 表，用于持久化存储 RAG 处理链路的全局追踪信息。
 */
@Data
@Builder
@TableName("trace_record")
public class TraceRecord {
    /**
     * 全链路唯一 traceId
     */
    @TableId(value = "trace_id", type = IdType.INPUT)
    private String traceId;

    /**
     * 根节点名称或任务名
     */
    private String name;

    /**
     * 链路开始时间戳（毫秒）
     */
    private Long startTime;

    /**
     * 当前链路状态（如 running/failed/success）
     */
    private String status;

    /**
     * 错误消息（若发生异常）
     */
    private String errorMessage;
}
