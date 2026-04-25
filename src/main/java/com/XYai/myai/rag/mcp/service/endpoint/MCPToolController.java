package com.XYai.myai.rag.mcp.service.endpoint;

import com.XYai.myai.config.Result;
import com.XYai.myai.rag.mcp.service.MCPService;
import com.XYai.myai.rag.mcp.tools.POJO.MCPRequest;
import com.XYai.myai.rag.mcp.tools.POJO.MCPResponse;
import com.XYai.myai.rag.mcp.tools.POJO.MCPTool;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

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

