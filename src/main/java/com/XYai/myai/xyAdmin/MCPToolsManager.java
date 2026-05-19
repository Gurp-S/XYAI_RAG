package com.XYai.myai.xyAdmin;

import com.XYai.myai.config.Result;
import com.XYai.myai.rag.mcp.McpToolToggleRegistry;
import com.XYai.myai.rag.mcp.ToolDecisionManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/xyAdmin/mcp")
public class MCPToolsManager {

    @Resource
    private McpToolToggleRegistry toolToggleRegistry;

    @Resource
    private ToolDecisionManager toolDecisionManager;

    /**
     * 获取所有 MCP 工具信息（名称、描述、启用状态）
     */
    @GetMapping("/tools")
    public Result<List<Map<String, Object>>> getAllTools() {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, ToolCallback> callbackMap = toolDecisionManager.getToolCallbackMap();
        if (callbackMap != null) {
            for (Map.Entry<String, ToolCallback> entry : callbackMap.entrySet()) {
                Map<String, Object> info = new HashMap<>();
                info.put("name", entry.getKey());
                info.put("description", entry.getValue().getToolDefinition().description());
                info.put("enabled", toolToggleRegistry.isEnabled(entry.getKey()));
                result.add(info);
            }
        }
        return Result.success(result);
    }

    /**
     * 启用指定 MCP 工具
     */
    @PostMapping("/enable")
    public Result<String> openMCPTools(@RequestParam String toolName) {
        toolToggleRegistry.enable(toolName);
        log.info("管理员启用了MCP工具: {}", toolName);
        return Result.success("工具 [" + toolName + "] 已启用");
    }

    /**
     * 禁用指定 MCP 工具
     */
    @PostMapping("/disable")
    public Result<String> deleteMCPTool(@RequestParam String toolName) {
        toolToggleRegistry.disable(toolName);
        log.info("管理员禁用了MCP工具: {}", toolName);
        return Result.success("工具 [" + toolName + "] 已禁用");
    }

    /**
     * 获取单个 MCP 工具的详细信息
     */
    @GetMapping("/info")
    public Result<Map<String, Object>> getMCPInfo(@RequestParam String toolName) {
        Map<String, ToolCallback> callbackMap = toolDecisionManager.getToolCallbackMap();
        if (callbackMap != null && callbackMap.containsKey(toolName)) {
            ToolCallback cb = callbackMap.get(toolName);
            Map<String, Object> info = new HashMap<>();
            info.put("name", toolName);
            info.put("description", cb.getToolDefinition().description());
            info.put("enabled", toolToggleRegistry.isEnabled(toolName));
            info.put("inputSchema", cb.getToolDefinition().inputSchema());
            return Result.success(info);
        }
        return Result.error(404, "工具 [" + toolName + "] 未找到");
    }
}
