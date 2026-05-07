package com.XYai.myai.rag.mcp;

import com.XYai.myai.rag.mcp.tools.CommonTools;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 第三方工具统一配置类
 */
@Data
@Component
@ConfigurationProperties(prefix = "tool")
public class ToolProperties {

    /**
     * 全局开关：是否启用 MCP 工具注册（可用于快速在运行时关闭所有工具以排查 token 泄露）
     */
    private boolean enabled = true;

    private Amap amap = new Amap();
    private Bilibili bilibili = new Bilibili();
    private CommonTools commonTools = new CommonTools();

    @Data
    public static class Amap {
        private String apiKey;
        private boolean enabled = true;
    }

    @Data
    public static class Bilibili {
        private boolean enabled = true;
    }

    @Data
    public static class CommonTools {
        private boolean enabled = true;
    }
}