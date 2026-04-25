package com.XYai.myai.rag.mcp.client;

import com.XYai.myai.rag.mcp.tools.POJO.MCPRequest;
import com.XYai.myai.rag.mcp.tools.POJO.MCPResponse;

/**
 * MCPClient 客户端接口
 * 定义应用程序调用外部远端 MCP 服务的通讯规范。
 */
public interface MCPClient {

    /**
     * 发起 MCP 工具调用请求至远端 Server
     */
    MCPResponse call(MCPRequest request);
}
