package com.XYai.myai.rag.mcp.tools;

import com.XYai.myai.rag.mcp.tools.POJO.MCPTool;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.Map;

/**
 * LLMMCPParameterExtractor 实现类
 * 实际逻辑应通过调用本地或远程 LLM 模型。
 * 该实现负责根据工具 Prompt 将用户输入转化为结构化参数。
 */
@Service
public class LLMMCPParameterExtractor implements MCPParameterExtractor {

    @Override
    public Map<String, Object> extractParameters(String userInput, MCPTool toolDefinition) {
        // TODO: 真正的 LLM 调用逻辑 (如：ChatClient.call())
        // 在此处构造 System Prompt 指引 LLM 仅提取定义的 Parameters
        return Collections.emptyMap();
    }
}
