package com.XYai.myai.rag.chat;

import com.XYai.myai.rag.chat.pojo.ModelRouterProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型健康状态存储
 */
@Slf4j
@Component
public class ModelHealthStore {

    @Resource
    private ModelRouterProperties routerProperties;

    private CircuitBreakerRegistry breakerRegistry;
    private final Map<String, CircuitBreaker> breakers = new ConcurrentHashMap<>();

    // 成功/失败计数
    private final Map<String, AtomicLong> successCount = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> failureCount = new ConcurrentHashMap<>();

    // 延迟统计
    private final Map<String, Deque<Long>> latencyWindow = new ConcurrentHashMap<>();
    private static final int LATENCY_WINDOW_SIZE = 100;

    // 最近错误
    private final Map<String, String> lastError = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        this.breakerRegistry = CircuitBreakerRegistry.ofDefaults();

        for (ModelRouterProperties.ModelCandidate candidate : routerProperties.getCandidates()) {
            if (!candidate.isEnabled()) continue;

            CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                    .failureRateThreshold(candidate.getCircuitBreaker().getFailureRateThreshold())
                    .waitDurationInOpenState(Duration.ofMillis(
                            candidate.getCircuitBreaker().getWaitDurationInOpenState()))
                    .slidingWindowSize(candidate.getCircuitBreaker().getSlidingWindowSize())
                    .minimumNumberOfCalls(candidate.getCircuitBreaker().getMinimumNumberOfCalls())
                    .build();

            CircuitBreaker breaker = CircuitBreaker.of(candidate.getName(), config);
            breakers.put(candidate.getName(), breaker);

            successCount.put(candidate.getName(), new AtomicLong(0));
            failureCount.put(candidate.getName(), new AtomicLong(0));
            latencyWindow.put(candidate.getName(), new ArrayDeque<>());

            breaker.getEventPublisher()
                    .onStateTransition(event -> log.warn("熔断器 [{}] 状态变化: {} -> {}",
                            event.getCircuitBreakerName(),
                            event.getStateTransition().getFromState(),
                            event.getStateTransition().getToState()));

            log.info("初始化熔断器: {} (阈值={}%)",
                    candidate.getName(),
                    candidate.getCircuitBreaker().getFailureRateThreshold());
        }
    }

    public CircuitBreaker getBreaker(String modelName) {
        return breakers.getOrDefault(modelName,
                breakerRegistry.circuitBreaker(modelName, CircuitBreakerConfig.ofDefaults()));
    }

    public boolean isHealthy(String modelName) {
        CircuitBreaker breaker = getBreaker(modelName);
        if (breaker == null) return false;

        // 熔断器开启 = 不健康
        if (breaker.getState() == CircuitBreaker.State.OPEN) {
            return false;
        }

        // 成功率过低 = 不健康
        double successRate = getSuccessRate(modelName);
        return getTotalCalls(modelName) <= 10 || !(successRate < 0.5);
    }

    public void recordSuccess(String modelName, long durationMs) {
        CircuitBreaker breaker = getBreaker(modelName);
        if (breaker != null) {
            breaker.onSuccess(0, TimeUnit.NANOSECONDS);
        }

        successCount.computeIfAbsent(modelName, k -> new AtomicLong()).incrementAndGet();

        Deque<Long> window = latencyWindow.computeIfAbsent(modelName, k -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(durationMs);
            while (window.size() > LATENCY_WINDOW_SIZE) {
                window.removeFirst();
            }
        }

        lastError.remove(modelName);
    }

    public void recordFailure(String modelName, Exception e) {
        CircuitBreaker breaker = getBreaker(modelName);
        if (breaker != null) {
            // 修复：使用 onError 方法
            breaker.onError(0, TimeUnit.NANOSECONDS, e);
        }

        failureCount.computeIfAbsent(modelName, k -> new AtomicLong()).incrementAndGet();
        lastError.put(modelName, e.getMessage());
    }

    public double getSuccessRate(String modelName) {
        long success = successCount.getOrDefault(modelName, new AtomicLong(0)).get();
        long failure = failureCount.getOrDefault(modelName, new AtomicLong(0)).get();
        long total = success + failure;

        if (total == 0) return 1.0;
        return (double) success / total;
    }

    public double getAvgLatency(String modelName) {
        Deque<Long> window = latencyWindow.get(modelName);
        if (window == null || window.isEmpty()) return 0;

        synchronized (window) {
            return window.stream().mapToLong(Long::longValue).average().orElse(0);
        }
    }

    public long getTotalCalls(String modelName) {
        long success = successCount.getOrDefault(modelName, new AtomicLong(0)).get();
        long failure = failureCount.getOrDefault(modelName, new AtomicLong(0)).get();
        return success + failure;
    }

    public String getBreakerState(String modelName) {
        CircuitBreaker breaker = getBreaker(modelName);
        return breaker != null ? breaker.getState().toString() : "UNKNOWN";
    }

    public Map<String, Object> getAllHealthStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        for (ModelRouterProperties.ModelCandidate candidate : routerProperties.getCandidates()) {
            String name = candidate.getName();
            Map<String, Object> modelStatus = new LinkedHashMap<>();
            modelStatus.put("enabled", candidate.isEnabled());
            modelStatus.put("breakerState", getBreakerState(name));
            modelStatus.put("healthy", isHealthy(name));
            modelStatus.put("successRate", String.format("%.2f%%", getSuccessRate(name) * 100));
            modelStatus.put("avgLatency", String.format("%.0fms", getAvgLatency(name)));
            modelStatus.put("totalCalls", getTotalCalls(name));

            if (lastError.containsKey(name)) {
                modelStatus.put("lastError", lastError.get(name));
            }

            status.put(name, modelStatus);
        }

        return status;
    }

    /**
     * 重置指定模型的状态
     */
    public void reset(String modelName) {
        CircuitBreaker breaker = getBreaker(modelName);
        if (breaker != null) {
            breaker.reset();
        }

        successCount.getOrDefault(modelName, new AtomicLong(0)).set(0);
        failureCount.getOrDefault(modelName, new AtomicLong(0)).set(0);

        Deque<Long> window = latencyWindow.get(modelName);
        if (window != null) {
            window.clear();
        }

        lastError.remove(modelName);

        log.info("已重置模型 [{}] 的健康状态", modelName);
    }
}