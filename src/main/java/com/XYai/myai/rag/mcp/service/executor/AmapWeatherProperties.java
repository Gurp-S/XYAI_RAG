package com.XYai.myai.rag.mcp.service.executor;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "mcp.weather.amap")
public class AmapWeatherProperties {

    /** 高德天气 API Key */
    private String key;

    /** 高德天气 API 地址 */
    private String baseUrl = "https://restapi.amap.com/v3/weather/weatherInfo";
}