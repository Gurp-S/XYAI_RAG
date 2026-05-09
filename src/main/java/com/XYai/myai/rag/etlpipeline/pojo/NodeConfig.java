package com.XYai.myai.rag.etlpipeline.pojo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NodeConfig implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 节点唯一标识
     */
    private String nodeId;
    /**
     * 节点类型：fetcher/parser/enricher/chunker/indexer
     */
    private String nodeType;
    /**
     * 节点参数
     */
    private JsonNode settings;
    /**
     * 节点执行条件
     */
    private JsonNode condition;
    /**
     * 下一节点ID
     */
    private String nextNodeId;
}

