package com.XYai.myai.rag.mcp.POJO;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.Set;

@Data
@Builder
public class McpToolDecision {
    int id;
    String toolName;
    Map<String,Object> arguments;
}
