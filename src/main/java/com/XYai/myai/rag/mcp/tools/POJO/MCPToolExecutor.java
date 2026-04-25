package com.XYai.myai.rag.mcp.tools.POJO;

/**
 * MCPToolExecutor 执行器接口
 * 定义工具执行、获取工具定义的标准接口。
 */
public interface MCPToolExecutor {

    /**
     * 获取工具的静态元数据定义 (MCPTool)
     */
    MCPTool getDefinition();

    /**
     * 执行具体的工具操作
     *
     * @param request 封装后的 MCP 请求（带参数）
     * @return 业务结果响应
     */
    MCPResponse execute(MCPRequest request);
}
