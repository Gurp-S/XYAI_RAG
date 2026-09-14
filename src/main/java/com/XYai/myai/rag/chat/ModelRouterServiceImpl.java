package com.XYai.myai.rag.chat;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
    /** 离线 Mock 模型（rag.mock-llm.enabled=true 时才存在，用于无额度/断网环境验证链路） */
    private final ObjectProvider<MockLlmResponder> mockResponder;

    private MockLlmResponder mock() {
        return mockResponder.getIfAvailable();
    }

    @Override
    public String route(String prompt, Long sessionId) {
        MockLlmResponder mock = mock();
        if (mock != null) {
            log.info("[MOCK_LLM] 普通调用被拦截 - sessionId: {}", sessionId);
            return mock.answer(prompt);
        }
        log.debug("路由调用 - sessionId: {}", sessionId);
        return routingExecutor.execute(prompt, sessionId);
    }

    @Override
    public String routeWithPreferred(String prompt, String preferredModel, Long sessionId) {
        MockLlmResponder mock = mock();
        if (mock != null) {
            log.info("[MOCK_LLM] 指定模型调用被拦截 - 首选模型: {}", preferredModel);
            return mock.answer(prompt);
        }
        log.debug("指定模型路由 - 首选模型: {}, sessionId: {}", preferredModel, sessionId);
        return routingExecutor.executeWithPreferred(prompt, preferredModel, sessionId);
    }

    @Override
    public String routeFast(String prompt, Long sessionId) {
        MockLlmResponder mock = mock();
        if (mock != null) {
            log.info("[MOCK_LLM] 快速模式调用被拦截 - sessionId: {}", sessionId);
            return mock.answer(prompt);
        }
        log.debug("快速模式路由 - sessionId: {}", sessionId);
        return routingExecutor.executeConcurrent(prompt, sessionId, 5000L);
    }

    @Override
    public String routeStream(String prompt, Long sessionId,
                              Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        MockLlmResponder mock = mock();
        if (mock != null) {
            log.info("[MOCK_LLM] 流式调用被拦截 - sessionId: {}", sessionId);
            mock.streamAnswer(prompt, onChunk, onError, onComplete);
            return mock.getModelName();
        }
        log.debug("流式路由调用 - sessionId: {}", sessionId);
        return routingExecutor.executeStream(prompt, sessionId, onChunk, onError, onComplete);
    }

    @Override
    public String routeWithPreferredStream(String prompt, String preferredModel, Long sessionId,
                                    Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        MockLlmResponder mock = mock();
        if (mock != null) {
            log.info("[MOCK_LLM] 指定模型流式调用被拦截 - 首选模型: {}", preferredModel);
            mock.streamAnswer(prompt, onChunk, onError, onComplete);
            return mock.getModelName();
        }
        log.debug("指定模型流式路由 - 首选模型: {}, sessionId: {}", preferredModel, sessionId);
        return routingExecutor.executeWithPreferredStream(prompt, preferredModel, sessionId, onChunk, onError, onComplete);
    }

    @Override
    public String routeFastStream(String prompt, Long sessionId,
                           Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        MockLlmResponder mock = mock();
        if (mock != null) {
            log.info("[MOCK_LLM] 快速模式流式调用被拦截 - sessionId: {}", sessionId);
            mock.streamAnswer(prompt, onChunk, onError, onComplete);
            return mock.getModelName();
        }
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
