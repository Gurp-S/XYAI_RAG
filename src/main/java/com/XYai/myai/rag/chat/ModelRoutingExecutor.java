package com.XYai.myai.rag.chat;

import com.XYai.myai.config.MultiChatClientConfig;
import com.XYai.myai.rag.chat.pojo.ModelCandidateEntity;
import com.XYai.myai.rag.chat.pojo.ModelRouterProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 模型路由执行器
 * 负责执行模型调用、降级切换
 */
@Slf4j
@Data
@Component
public class ModelRoutingExecutor {

    @Resource
    private ModelSelector modelSelector;

    @Resource
    private ModelHealthStore healthStore;

    @Resource
    @Qualifier("modelClientMap")
    private Map<String, ChatClient> modelClientMap;

    @Resource
    private ModelRouterProperties routerProperties;

    // ==================== 同步调用（保持不变） ====================

    /**
     * 执行路由调用（自动降级）
     */
    public String execute(String prompt, String sessionId) {
        long startTime = System.currentTimeMillis();
        List<String> fallbackChain = modelSelector.getFallbackChain();
        log.info("降级链路: {}", fallbackChain);

        if (fallbackChain.isEmpty()) {
            return "暂无可用模型，请稍后重试";
        }

        Exception lastException = null;
        for (String modelName : fallbackChain) {
            try {
                String result = callModel(modelName, prompt, sessionId);
                if (result != null) {
                    long duration = System.currentTimeMillis() - startTime;
                    log.info("模型 [{}] 调用成功，耗时: {}ms", modelName, duration);
                    return result;
                }
            } catch (Exception e) {
                lastException = e;
                log.warn("模型 [{}] 调用失败: {}", modelName, e.getMessage());
                healthStore.recordFailure(modelName, e);
            }
        }

        log.error("所有模型均不可用，最后异常: {}", lastException != null ? lastException.getMessage() : "无");
        return "服务繁忙，请稍后重试";
    }

    public String executeWithPreferred(String prompt, String preferredModel, String sessionId) {
        long startTime = System.currentTimeMillis();
        if (!healthStore.isHealthy(preferredModel)) {
            log.warn("首选模型 [{}] 不健康，切换到降级链路", preferredModel);
            return execute(prompt, sessionId);
        }
        try {
            String result = callModel(preferredModel, prompt, sessionId);
            long duration = System.currentTimeMillis() - startTime;
            log.info("首选模型 [{}] 调用成功，耗时: {}ms", preferredModel, duration);
            return result;
        } catch (Exception e) {
            log.warn("首选模型 [{}] 调用失败: {}", preferredModel, e.getMessage());
            healthStore.recordFailure(preferredModel, e);
            return execute(prompt, sessionId);
        }
    }

    public String executeConcurrent(String prompt, String sessionId, long timeoutMs) {
        List<String> candidates = modelSelector.getAvailableModels().stream()
                .limit(3)
                .map(ModelCandidateEntity::getName)
                .toList();

        if (candidates.isEmpty()) {
            return execute(prompt, sessionId);
        }

        log.info("并发调用 - 候选模型: {}", candidates);
        long startTime = System.currentTimeMillis();

        List<CompletableFuture<Map.Entry<String, String>>> futures = candidates.stream()
                .map(modelName -> CompletableFuture.supplyAsync(() -> {
                    try {
                        String result = callModel(modelName, prompt, sessionId);
                        return Map.entry(modelName, result);
                    } catch (Exception e) {
                        healthStore.recordFailure(modelName, e);
                        return Map.entry(modelName, "");
                    }
                }))
                .toList();

        try {
            CompletableFuture<Map.Entry<String, String>> anyResult = CompletableFuture
                    .anyOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(obj -> (Map.Entry<String, String>) obj);

            Map.Entry<String, String> result = anyResult.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result.getValue() != null) {
                long duration = System.currentTimeMillis() - startTime;
                log.info("并发调用完成，最快模型: {}，耗时: {}ms", result.getKey(), duration);
                return result.getValue();
            }
        } catch (Exception e) {
            log.warn("并发调用异常: {}", e.getMessage());
        }

        return execute(prompt, sessionId);
    }

    // ==================== 流式调用（回调模式，返回模型名称） ====================

    /**
     * 自动路由流式调用（优先默认模型，否则降级）
     *
     * @return 实际使用的模型名称
     */
    public String executeStream(String prompt, String sessionId,
                                Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        String defaultModel = routerProperties.getFeatureModel("chat_default");
        if (defaultModel != null && healthStore.isHealthy(defaultModel)
                && (modelClientMap.containsKey(defaultModel)
                || MultiChatClientConfig.getMutableModelMap().containsKey(defaultModel))) {
            ModelCandidateEntity candidate = routerProperties.findByName(defaultModel);
            if (candidate != null && candidate.isEnabled()) {
                log.info("流式调用使用默认模型: {}", defaultModel);
                try {
                    invokeModelStream(defaultModel, prompt, sessionId, onChunk, onComplete);
                    return defaultModel;
                } catch (Exception e) {
                    log.warn("默认模型 [{}] 流式调用失败: {}", defaultModel, e.getMessage());
                }
            }
        }
        return doFallbackStream(prompt, sessionId, onChunk, onError, onComplete);
    }

    /**
     * 指定模型流式调用（失败自动降级）
     *
     * @return 实际使用的模型名称
     */
    public String executeWithPreferredStream(String prompt, String preferredModel, String sessionId,
                                              Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        if (!healthStore.isHealthy(preferredModel)) {
            log.warn("首选模型 [{}] 不健康，切换到降级链路", preferredModel);
            return executeStream(prompt, sessionId, onChunk, onError, onComplete);
        }

        log.info("使用首选模型 [{}] 进行流式调用", preferredModel);
        try {
            invokeModelStream(preferredModel, prompt, sessionId, onChunk, onComplete);
            return preferredModel;
        } catch (Exception e) {
            log.warn("首选模型 [{}] 流式调用失败: {}", preferredModel, e.getMessage());
            healthStore.recordFailure(preferredModel, e);
            return executeStream(prompt, sessionId, onChunk, onError, onComplete);
        }
    }

    /**
     * 快速模式流式调用（取候选模型中最快可用的）
     *
     * @return 实际使用的模型名称
     */
    public String executeConcurrentStream(String prompt, String sessionId, long timeoutMs,
                                           Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        List<String> candidates = modelSelector.getAvailableModels().stream()
                .limit(3)
                .map(ModelCandidateEntity::getName)
                .toList();

        if (candidates.isEmpty()) {
            return executeStream(prompt, sessionId, onChunk, onError, onComplete);
        }

        log.info("快速模式流式调用 - 候选模型: {}", candidates);
        for (String modelName : candidates) {
            if (healthStore.isHealthy(modelName)) {
                log.info("快速模式流式调用使用模型: {}", modelName);
                try {
                    invokeModelStream(modelName, prompt, sessionId, onChunk, onComplete);
                    return modelName;
                } catch (Exception e) {
                    log.warn("模型 [{}] 流式调用失败，切换到降级链路", modelName);
                }
            }
        }
        return executeStream(prompt, sessionId, onChunk, onError, onComplete);
    }

    // ==================== 降级逻辑（内部使用） ====================

    /**
     * 同步遍历降级链，调用第一个健康模型的流式方法。
     *
     * @return 实际使用的模型名称，全部失败返回 null
     */
    private String doFallbackStream(String prompt, String sessionId,
                                    Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete) {
        List<String> fallbackChain = modelSelector.getFallbackChain();
        log.info("降级链路: {}", fallbackChain);

        if (fallbackChain.isEmpty()) {
            onError.accept(new RuntimeException("暂无可用模型，请稍后重试"));
            return null;
        }

        for (String modelName : fallbackChain) {
            try {
                if (healthStore.isHealthy(modelName)) {
                    log.info("降级链路使用模型: {}", modelName);
                    invokeModelStream(modelName, prompt, sessionId, onChunk, onComplete);
                    return modelName;
                }
            } catch (Exception e) {
                log.warn("模型 [{}] 健康检查异常: {}", modelName, e.getMessage());
            }
        }

        log.error("所有模型均不可用");
        onError.accept(new RuntimeException("所有模型均不可用，请稍后重试"));
        return null;
    }

    // ==================== 底层调用（私有） ====================

    /**
     * 实际调用模型流式接口（内部方法，使用 Flux.toIterable() 桥接为阻塞）。
     * 成功时调用 onComplete，失败时抛出异常（由上层处理降级）。
     */
    private void invokeModelStream(String modelName, String prompt, String sessionId,
                                   Consumer<String> onChunk, Runnable onComplete) {
        CircuitBreaker breaker = healthStore.getBreaker(modelName);
        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            throw new RuntimeException("模型 [" + modelName + "] 处于熔断状态");
        }

        ChatClient client = modelClientMap.get(modelName);
        if (client == null) {
            client = MultiChatClientConfig.getMutableModelMap().get(modelName);
        }
        if (client == null) {
            throw new RuntimeException("未找到模型对应的ChatClient: " + modelName);
        }

        long startTime = System.currentTimeMillis();
        try {
            for (String chunk : client.prompt(prompt).stream().content().toIterable()) {
                onChunk.accept(chunk);
            }
            long duration = System.currentTimeMillis() - startTime;
            healthStore.recordSuccess(modelName, duration);
            log.debug("模型 [{}] 流式响应完成，耗时: {}ms", modelName, duration);
            onComplete.run();
        } catch (Exception e) {
            healthStore.recordFailure(modelName, e);
            log.error("模型 [{}] 流式响应失败: {}", modelName, e.getMessage());
            throw e;
        }
    }

    /**
     * 同步调用模型（带熔断保护）
     */
    private String callModel(String modelName, String prompt, String sessionId) throws Exception {
        CircuitBreaker breaker = healthStore.getBreaker(modelName);
        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            throw new RuntimeException("模型 [" + modelName + "] 处于熔断状态");
        }

        long startTime = System.currentTimeMillis();
        try {
            String result = breaker.executeSupplier(() -> {
                ChatClient client = modelClientMap.get(modelName);
                if (client == null) {
                    client = MultiChatClientConfig.getMutableModelMap().get(modelName);
                }
                if (client == null) {
                    throw new RuntimeException("未找到模型对应的ChatClient: " + modelName);
                }
                return client.prompt(prompt).call().content();
            });

            long duration = System.currentTimeMillis() - startTime;
            healthStore.recordSuccess(modelName, duration);
            return result;
        } catch (Exception e) {
            healthStore.recordFailure(modelName, e);
            throw e;
        }
    }

    public Map<String, Object> getHealthStatus() {
        return healthStore.getAllHealthStatus();
    }
}