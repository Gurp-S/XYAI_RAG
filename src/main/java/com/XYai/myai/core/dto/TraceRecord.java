package com.XYai.myai.core.dto;

import lombok.Builder;
import lombok.Data;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@Data
@Builder
@TableName("trace_record")
public class TraceRecord {
    //整个请求的记录实体(包含 traceId, rootName, startTime)
    private String traceId;

    private String name;

    private Long startTime;

    private String status;

    private String errorMessage;
}
