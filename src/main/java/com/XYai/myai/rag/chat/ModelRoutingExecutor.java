package com.XYai.myai.rag.chat;

import com.XYai.myai.rag.chat.pojo.ModelRouterProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    /**
     * 执行路由调用（自动降级）
     * @param prompt 用户输入
     * @param sessionId 会话ID
     * @return 模型响应
     */
    public String execute(String prompt, String sessionId) {
        long startTime = System.currentTimeMillis();

        // 获取降级链路
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

    /**
     * 指定模型流式执行（失败自动降级）
     */
    public Flux<String> executeWithPreferredStream(String prompt, String preferredModel, String sessionId) {
        if (!healthStore.isHealthy(preferredModel)) {
            log.warn("首选模型 [{}] 不健康，切换到降级链路", preferredModel);
            return executeStream(prompt, sessionId);
        }

        log.info("使用首选模型 [{}] 进行流式调用", preferredModel);
        return callModelStream(preferredModel, prompt, sessionId)
                .onErrorResume(e -> {
                    log.warn("首选模型 [{}] 流式调用失败: {}", preferredModel, e.getMessage());
                    healthStore.recordFailure(preferredModel, (Exception) e);
                    return executeStream(prompt, sessionId);
                });
    }

    /**
     * 并发流式调用（取最快响应）
     */
    public Flux<String> executeConcurrentStream(String prompt, String sessionId, long timeoutMs) {
        List<String> candidates = modelSelector.getAvailableModels().stream()
                .limit(3)
                .map(ModelRouterProperties.ModelCandidate::getName)
                .toList();

        if (candidates.isEmpty()) {
            return executeStream(prompt, sessionId);
        }

        log.info("并发流式调用 - 候选模型: {}", candidates);

        // 选择第一个健康的模型进行流式调用
        // 注意：真正的并发流式需要更复杂的实现，这里简化为选择最优模型
        for (String modelName : candidates) {
            if (healthStore.isHealthy(modelName)) {
                log.info("并发流式调用使用模型: {}", modelName);
                return callModelStream(modelName, prompt, sessionId)
                        .onErrorResume(e -> {
                            log.warn("模型 [{}] 流式调用失败，切换到降级链路", modelName);
                            return executeStream(prompt, sessionId);
                        });
            }
        }

        return executeStream(prompt, sessionId);
    }

    // 在 ModelRoutingExecutor 中添加流式调用方法
    public Flux<String> executeStream(String prompt, String sessionId) {
        List<String> fallbackChain = modelSelector.getFallbackChain();
        log.info("流式调用降级链路: {}", fallbackChain);

        if (fallbackChain.isEmpty()) {
            return Flux.just("暂无可用模型，请稍后重试");
        }

        return Flux.defer(() -> {
            for (String modelName : fallbackChain) {
                try {
                    if (healthStore.isHealthy(modelName)) {
                        return callModelStream(modelName, prompt, sessionId)
                                .doOnComplete(() -> log.info("模型 [{}] 流式调用完成", modelName));
                    }
                } catch (Exception e) {
                    log.warn("模型 [{}] 流式调用失败: {}", modelName, e.getMessage());
                    healthStore.recordFailure(modelName, e);
                }
            }
            return Flux.just("所有模型均不可用，请稍后重试");
        });
    }

    private Flux<String> callModelStream(String modelName, String prompt, String sessionId) {
        CircuitBreaker breaker = healthStore.getBreaker(modelName);

        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            return Flux.error(new RuntimeException("模型 [" + modelName + "] 处于熔断状态"));
        }

        org.springframework.ai.chat.client.ChatClient client = modelClientMap.get(modelName);
        if (client == null) {
            return Flux.error(new RuntimeException("未找到模型对应的ChatClient: " + modelName));
        }

        long startTime = System.currentTimeMillis();
        StringBuilder fullResponse = new StringBuilder();

        return client.prompt(prompt).stream().content()
                .doOnNext(fullResponse::append)
                .doOnComplete(() -> {
                    long duration = System.currentTimeMillis() - startTime;
                    healthStore.recordSuccess(modelName, duration);
                    log.debug("模型 [{}] 流式响应完成，耗时: {}ms", modelName, duration);
                })
                .doOnError(e -> {
                    healthStore.recordFailure(modelName, (Exception) e);
                    log.error("模型 [{}] 流式响应失败: {}", modelName, e.getMessage());
                });
    }

    /**
     * 指定模型执行（失败自动降级）
     * @param prompt 用户输入
     * @param preferredModel 首选模型
     * @param sessionId 会话ID
     * @return 模型响应
     */
    public String executeWithPreferred(String prompt, String preferredModel, String sessionId) {
        long startTime = System.currentTimeMillis();

        // 检查首选模型是否可用
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
            // 降级到默认路由
            return execute(prompt, sessionId);
        }
    }

    /**
     * 并发调用（取最快响应）
     */
    public String executeConcurrent(String prompt, String sessionId, long timeoutMs) {
        List<String> candidates = modelSelector.getAvailableModels().stream()
                .limit(3)
                .map(ModelRouterProperties.ModelCandidate::getName)
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
            CompletableFuture<Map.Entry<String, String>> anyResult =
                    CompletableFuture.anyOf(futures.toArray(new CompletableFuture[0]))
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

        // 降级到顺序执行
        return execute(prompt, sessionId);
    }

    /**
     * 调用模型（带熔断保护）
     */
    private String callModel(String modelName, String prompt, String sessionId) throws Exception {
        CircuitBreaker breaker = healthStore.getBreaker(modelName);

        // 熔断检查
        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            throw new RuntimeException("模型 [" + modelName + "] 处于熔断状态");
        }

        long startTime = System.currentTimeMillis();

        try {
            String result = breaker.executeSupplier(() -> {
                org.springframework.ai.chat.client.ChatClient client = modelClientMap.get(modelName);
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

    /**
     * 获取所有模型的健康状态
     */
    public Map<String, Object> getHealthStatus() {
        return healthStore.getAllHealthStatus();
    }
}