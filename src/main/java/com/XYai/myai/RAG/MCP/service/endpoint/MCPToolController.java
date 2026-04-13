package com.XYai.myai.RAG.MCP.service.endpoint;

import com.XYai.myai.Config.Result;
import com.XYai.myai.RAG.MCP.service.MCPService;
import com.XYai.myai.RAG.MCP.tools.POJO.MCPRequest;
import com.XYai.myai.RAG.MCP.tools.POJO.MCPResponse;
import com.XYai.myai.RAG.MCP.tools.POJO.MCPTool;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Map;

@RestController
@RequestMapping("/mcp")
@RequiredArgsConstructor
public class MCPToolController {

    private final MCPService mcpService;

    @GetMapping("/tools")
    public Result<Collection<MCPTool>> tools() {
        return Result.success(mcpService.listTools());
    }

    @PostMapping("/invoke")
    public Result<MCPResponse> invoke(@RequestBody InvokeRequest request) {
        MCPRequest mcpRequest = MCPRequest.builder()
                .toolId(request.toolId())
                .userId(request.userId())
                .parameters(request.parameters())
                .context(request.context())
                .serverUrl(request.serverUrl())
                .build();
        return Result.success(mcpService.invoke(mcpRequest));
    }

    public record InvokeRequest(String toolId,
                                String userId,
                                Map<String, Object> parameters,
                                String context,
                                String serverUrl) {
    }
}

