package com.XYai.myai.rag.mcp.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpToolDecision {
    int id;
    String toolName;
    Map<String,Object> arguments;
}
