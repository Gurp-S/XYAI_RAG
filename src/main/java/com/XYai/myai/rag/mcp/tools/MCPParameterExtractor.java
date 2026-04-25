package com.XYai.myai.rag.mcp.tools;

import com.XYai.myai.rag.mcp.tools.POJO.MCPTool;

import java.util.Map;

/**
 * MCPParameterExtractor 参数提取接口
 * 定义从用户模糊回复或问题描述中，提取符合工具定义参数映射的规范建议。
 */
public interface MCPParameterExtractor {

    /**
     * 根据工具元定义从用户输入的中提取所需的参数
     *
     * @param userInput      用户提供的原生交互信息或上下文
     * @param toolDefinition 目标工具定义的参数 Schema 描述
     * @return 提取成功的参数 KV 对
     */
    Map<String, Object> extractParameters(String userInput, MCPTool toolDefinition);
}
