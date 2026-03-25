package com.XYai.myai.impl;

import com.XYai.myai.core.mcp.MCPToolExecutor;
import com.XYai.myai.core.mcp.ToolDescriptor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * MCP 工具桩实现，默认回显请求内容。
 */
@Service
public class StubMCPToolExecutor implements MCPToolExecutor<Map<String,Object>, Map<String,Object>> {

    /**
     * 执行工具调用（回显模式）。
     */
    @Override
    public CompletableFuture<Map<String, Object>> execute(Map<String, Object> req) {
        return CompletableFuture.completedFuture(Map.of("echo", req));
    }

    /**
     * 返回当前桩工具的元信息。
     */
    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor("stub-tool", "echo tool", Duration.ofSeconds(5), Map.of());
    }
}

