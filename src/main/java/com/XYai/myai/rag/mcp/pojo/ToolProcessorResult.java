package com.XYai.myai.rag.mcp.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolProcessorResult {
    private String toolName;
    private boolean success;
    private String result;

    public static ToolProcessorResult success(String toolName, String result) {
        return new ToolProcessorResult(toolName, true, result);
    }

    public static ToolProcessorResult fail(String toolName, String error) {
        return new ToolProcessorResult(toolName, false, error);
    }
}