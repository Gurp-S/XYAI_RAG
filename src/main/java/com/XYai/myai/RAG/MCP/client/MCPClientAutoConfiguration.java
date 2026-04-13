package com.XYai.myai.RAG.MCP.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * MCP 客户端自动配置类
 * 将相关工具 bean 自动化注入 Spring 容器，减少集成负担。
 */
@Configuration
public class MCPClientAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    // 容器启动时可自动注册已发现的外部 MCP 服务
}
