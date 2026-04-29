package com.XYai.myai.rag.mcp;

import com.XYai.myai.rag.mcp.tools.AMapTool;
import com.XYai.myai.rag.mcp.tools.BilibiliTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class MCPToolConfig {

    private final ToolProperties toolProperties;
    private final AMapTool aMapTool;
    private final BilibiliTool bilibiliTool;

    @Bean
    public ToolCallbackProvider allToolsProvider() {
        List<Object> toolBeans = new ArrayList<>();

        log.info("===== 注册MCP工具 =====");

        // 高德
        if (toolProperties.getAmap() != null && toolProperties.getAmap().isEnabled()) {
            toolBeans.add(aMapTool);
            log.info("注册高德地图工具 AMapTool");
        }

        if (toolProperties.getBilibili() != null && toolProperties.getBilibili().isEnabled()) {
            toolBeans.add(bilibiliTool);
            log.info("注册B站工具 BilibiliTool");
        }

        log.info("===== 工具注册完成，共 {} 个 =====", toolBeans.size());

        return MethodToolCallbackProvider.builder()
                .toolObjects(toolBeans.toArray())
                .build();
    }
}