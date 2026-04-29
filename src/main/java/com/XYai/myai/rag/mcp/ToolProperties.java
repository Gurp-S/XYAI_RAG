package com.XYai.myai.rag.mcp;

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

    private Amap amap = new Amap();
    private Bilibili bilibili = new Bilibili();

    @Data
    public static class Amap {
        private String apiKey;
        private boolean enabled = true;
    }

    @Data
    public static class Bilibili {
        private boolean enabled = true;
    }
}