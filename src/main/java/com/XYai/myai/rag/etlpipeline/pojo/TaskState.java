package com.XYai.myai.rag.etlpipeline.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskState {
    private String taskId;
    private String status;
    private String currentNodeType;
    private String nodeLabel;
    private String message;
    private String displayText;
    private String eventType;
    private Integer progress;
    private Long startTime;
    private Long endTime;
    private String errorMessage;
}