package com.XYai.myai.rag.etlpipeline.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

// domain/pipeline/PipelineDefinition.java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineDefinition {
    private String id;          // 管道唯一标识
    private String name;        // 管道名称（如PDF文档摄取管道）
    private String description; // 管道描述
    @Builder.Default
    private List<NodeConfig> nodes = new ArrayList<>(); // 按执行顺序排列的节点列表
}