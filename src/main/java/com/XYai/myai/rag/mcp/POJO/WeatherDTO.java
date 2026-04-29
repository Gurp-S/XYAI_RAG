package com.XYai.myai.rag.mcp.POJO;

import lombok.Data;

@Data
public class WeatherDTO {
    private String city;        // 城市
    private String weather;     // 天气
    private String temperature; // 温度
    private String wind;        // 风向风力
    private String humidity;    // 湿度
}