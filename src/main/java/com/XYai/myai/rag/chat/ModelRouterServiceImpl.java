package com.XYai.myai.rag.chat;


import com.XYai.myai.rag.chat.pojo.StreamResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 模型路由服务实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelRouterServiceImpl implements ModelRouterService {

    private final ModelSelector modelSelector;
    private final ModelRoutingExecutor routingExecutor;
    private final ModelHealthStore healthStore;

    @Override
    public String route(String prompt, String sessionId) {
        log.debug("路由调用 - sessionId: {}", sessionId);
        return routingExecutor.execute(prompt, sessionId);
    }

    @Override
    public String routeWithPreferred(String prompt, String preferredModel, String sessionId) {
        log.debug("指定模型路由 - 首选模型: {}, sessionId: {}", preferredModel, sessionId);
        return routingExecutor.executeWithPreferred(prompt, preferredModel, sessionId);
    }

    @Override
    public String routeFast(String prompt, String sessionId) {
        log.debug("快速模式路由 - sessionId: {}", sessionId);
        return routingExecutor.executeConcurrent(prompt, sessionId, 5000L);
    }

    @Override
    public StreamResult routeStream(String prompt, String sessionId) {
        log.debug("流式路由调用 - sessionId: {}", sessionId);
        return routingExecutor.executeStream(prompt, sessionId);
    }

    @Override
    public StreamResult routeWithPreferredStream(String prompt, String preferredModel, String sessionId) {
        log.debug("指定模型流式路由 - 首选模型: {}, sessionId: {}", preferredModel, sessionId);
        return routingExecutor.executeWithPreferredStream(prompt, preferredModel, sessionId);
    }

    @Override
    public StreamResult routeFastStream(String prompt, String sessionId) {
        log.debug("快速模式流式路由 - sessionId: {}", sessionId);
        return routingExecutor.executeConcurrentStream(prompt, sessionId, 5000L);
    }

    @Override
    public Map<String, Object> getHealthStatus() {
        return healthStore.getAllHealthStatus();
    }

    @Override
    public void resetModel(String modelName) {
        healthStore.reset(modelName);
    }
}