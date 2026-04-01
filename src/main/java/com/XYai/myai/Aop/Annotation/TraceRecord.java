package com.XYai.myai.Aop.Annotation;

import lombok.Builder;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.TableName;

@Data
@Builder
@TableName("trace_record")
public class TraceRecord {
    /** 全链路唯一 traceId */
    private String traceId;

    /** 根节点名称或任务名 */
    private String name;

    /** 链路开始时间戳（毫秒） */
    private Long startTime;

    /** 当前链路状态（如 running/failed/success） */
    private String status;

    /** 错误消息（若发生异常） */
    private String errorMessage;
}
