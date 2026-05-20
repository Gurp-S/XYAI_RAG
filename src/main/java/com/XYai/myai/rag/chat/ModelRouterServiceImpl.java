package com.XYai.myai.rag.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.function.Consumer;

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
    public String routeStream(String prompt, String sessionId,
                              Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        log.debug("流式路由调用 - sessionId: {}", sessionId);
        return routingExecutor.executeStream(prompt, sessionId, onChunk, onError, onComplete);
    }

    @Override
    public String routeWithPreferredStream(String prompt, String preferredModel, String sessionId,
                                           Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        log.debug("指定模型流式路由 - 首选模型: {}, sessionId: {}", preferredModel, sessionId);
        return routingExecutor.executeWithPreferredStream(prompt, preferredModel, sessionId, onChunk, onError, onComplete);
    }

    @Override
    public String routeFastStream(String prompt, String sessionId,
                                  Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        log.debug("快速模式流式路由 - sessionId: {}", sessionId);
        return routingExecutor.executeConcurrentStream(prompt, sessionId, 5000L, onChunk, onError, onComplete);
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
