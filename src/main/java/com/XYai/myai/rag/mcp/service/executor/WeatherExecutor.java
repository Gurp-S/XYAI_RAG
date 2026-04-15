package com.XYai.myai.rag.mcp.service.executor;

import com.XYai.myai.rag.mcp.tools.POJO.MCPRequest;
import com.XYai.myai.rag.mcp.tools.POJO.MCPResponse;
import com.XYai.myai.rag.mcp.tools.POJO.MCPTool;
import com.XYai.myai.rag.mcp.tools.POJO.MCPToolExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class WeatherExecutor implements MCPToolExecutor {

    private static final String TOOL_ID = "weather.current";
    private static final String SOURCE = "amap";

    private final RestTemplate restTemplate;
    private final AmapWeatherProperties amapWeatherProperties;

    @Override
    public MCPTool getDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("city", Map.of("type", "string", "description", "城市名或高德adcode，如 北京/310000"));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", new String[]{"city"});

        return MCPTool.builder()
                .id(TOOL_ID)
                .name("weatherCurrent")
                .description("查询指定城市实时天气（高德）")
                .inputSchema("{\"type\":\"object\",\"required\":[\"city\"],\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"城市名或高德adcode\"}}}")
                .parameters(parameters)
                .serverUrl(amapWeatherProperties.getBaseUrl())
                .build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public MCPResponse execute(MCPRequest request) {
        Map<String, Object> input = request == null ? null : request.getParameters();
        if (input == null || input.get("city") == null || String.valueOf(input.get("city")).isBlank()) {
            return MCPResponse.builder()
                    .code(400)
                    .message("缺少必填参数 city")
                    .success(false)
                    .build();
        }

        if (amapWeatherProperties.getKey() == null || amapWeatherProperties.getKey().isBlank()) {
            return MCPResponse.builder()
                    .code(500)
                    .message("高德天气 key 未配置")
                    .success(false)
                    .build();
        }

        String city = String.valueOf(input.get("city")).trim();
        URI uri = UriComponentsBuilder.fromHttpUrl(amapWeatherProperties.getBaseUrl())
                .queryParam("key", amapWeatherProperties.getKey())
                .queryParam("city", city)
                .queryParam("extensions", "base")
                .queryParam("output", "JSON")
                .build(true)
                .toUri();

        try {
            Map<String, Object> response = restTemplate.getForObject(uri, Map.class);
            if (response == null) {
                return fail(502, "高德天气响应为空");
            }

            String status = String.valueOf(response.getOrDefault("status", "0"));
            if (!"1".equals(status)) {
                String info = String.valueOf(response.getOrDefault("info", "未知错误"));
                String infocode = String.valueOf(response.getOrDefault("infocode", ""));
                return fail(502, "高德天气查询失败: " + info + (infocode.isBlank() ? "" : " (" + infocode + ")"));
            }

            Object livesObj = response.get("lives");
            if (!(livesObj instanceof List<?> lives) || lives.isEmpty() || !(lives.get(0) instanceof Map<?, ?>)) {
                return fail(502, "高德天气返回数据格式异常");
            }

            Map<String, Object> live = (Map<String, Object>) lives.get(0);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("toolId", TOOL_ID);
            data.put("source", SOURCE);
            data.put("city", live.get("city"));
            data.put("adcode", live.get("adcode"));
            data.put("weather", live.get("weather"));
            data.put("temperature", live.get("temperature"));
            data.put("humidity", live.get("humidity"));
            data.put("windDirection", live.get("winddirection"));
            data.put("windPower", live.get("windpower"));
            data.put("reportTime", live.get("reporttime"));

            return MCPResponse.builder()
                    .code(200)
                    .message("天气查询成功")
                    .data(data)
                    .success(true)
                    .build();
        } catch (Exception ex) {
            log.error("调用高德天气接口失败, city={}, error={}", city, ex.getMessage(), ex);
            return fail(500, "天气服务调用异常: " + ex.getMessage());
        }
    }

    private MCPResponse fail(int code, String message) {
        return MCPResponse.builder()
                .code(code)
                .message(message)
                .success(false)
                .build();
    }
}