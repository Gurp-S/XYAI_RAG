package com.XYai.myai.RAG.MCP.client;

import com.XYai.myai.RAG.MCP.tools.POJO.MCPRequest;
import com.XYai.myai.RAG.MCP.tools.POJO.MCPResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * HttpMCPClient 客户端实现类
 * 核心逻辑：负责通过 REST/HTTP 协议，将负载发送到指定的 Server 端 URL。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HttpMCPClient implements MCPClient {

    private final RestTemplate restTemplate;

    @Override
    public MCPResponse call(MCPRequest request) {
        String serverUrl = request.getServerUrl();
        if (serverUrl == null || serverUrl.isEmpty()) {
            return MCPResponse.builder()
                    .code(400)
                    .message("工具执行失败：未发现有效的 ServerUrl (Remote Endpoint)")
                    .success(false)
                    .build();
        }

        try {
            log.info("MCP HTTP 工具发起调用: URL={}, Params={}", serverUrl, request.getParameters());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<MCPRequest> entity = new HttpEntity<>(request, headers);

            return restTemplate.postForObject(serverUrl, entity, MCPResponse.class);
        } catch (Exception e) {
            log.error("MCP HTTP 通信发生异常: ", e);
            return MCPResponse.builder()
                    .code(500)
                    .message("网络通信异常: " + e.getMessage())
                    .success(false)
                    .build();
        }
    }
}
