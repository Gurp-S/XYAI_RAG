package com.XYai.myai.mcp;

import java.util.concurrent.CompletableFuture;

/**
 * MCP 工具执行器接口。
 *
 * @param <TReq> 工具请求类型
 * @param <TResp> 工具响应类型
 */
public interface MCPToolExecutor<TReq, TResp> {

    /**
     * 异步执行工具调用。
     *
     * @param req 调用请求
     * @return 异步工具响应
     */
    CompletableFuture<TResp> execute(TReq req);

    /**
     * 返回工具元信息（名称、超时、输入规范等）。
     *
     * @return 工具描述
     */
    ToolDescriptor descriptor();
}

