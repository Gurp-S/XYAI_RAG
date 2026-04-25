package com.XYai.myai.rag.mcp.service;

import com.XYai.myai.rag.mcp.tools.POJO.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MCPService {

    private final MCPToolRegistry toolRegistry;

    //=======管理MCP工具=========
    public Collection<MCPTool> listTools() {
        return toolRegistry.getAllTools();
    }

    //=======请求MCP工具============
    public MCPResponse invoke(MCPRequest request) {
        if (request == null || request.getToolId() == null || request.getToolId().isBlank()) {
            return MCPResponse.builder()
                    .code(400)
                    .message("toolId 不能为空")
                    .success(false)
                    .build();
        }

        Optional<MCPToolExecutor> executorOptional = toolRegistry.getExecutor(request.getToolId());
        if (executorOptional.isEmpty()) {
            return MCPResponse.builder()
                    .code(404)
                    .message("未找到对应 MCP 工具: " + request.getToolId())
                    .success(false)
                    .build();
        }

        return executorOptional.get().execute(request);
    }
}
