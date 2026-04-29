package com.XYai.myai.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

@Slf4j
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate amapRestTemplate() {
        RestTemplate restTemplate = new RestTemplate();

        // 添加拦截器，统一设置请求头
        restTemplate.getInterceptors().add((request, body, execution) -> {
            request.getHeaders().set("User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            request.getHeaders().set("Accept", "application/json");
            request.getHeaders().set("Accept-Language", "zh-CN,zh;q=0.9");
            return execution.execute(request, body);
        });
        // 确保编码
        restTemplate.getMessageConverters().forEach(converter -> {
            if (converter instanceof StringHttpMessageConverter c) {
                c.setDefaultCharset(StandardCharsets.UTF_8);
            }
        });

        return restTemplate;
    }
}