package com.XYai.myai.rag.etlpipeline.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NodeLog implements Serializable {
    private String nodeName;
    private Instant timestamp;
    private String level;
    private String message;
    @Builder.Default
    private Map<String, Object> extra = new HashMap<>();
}