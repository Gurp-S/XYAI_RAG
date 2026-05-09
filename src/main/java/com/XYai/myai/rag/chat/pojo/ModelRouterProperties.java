package com.XYai.myai.rag.chat.pojo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * 模型路由配置属性
 * application.yml 配置示例见下文
 */
@Data
@Component
@ConfigurationProperties(prefix = "router")
public class ModelRouterProperties {

    /** 模型候选列表配置 */
    private List<ModelCandidate> candidates;

    /** 路由策略：round_robin, failover, latency_based, task_based */
    private String strategy = "failover";

    /** 首包探测超时时间（毫秒） */
    private long firstPacketTimeout = 5000L;

    /** 级联调用超时时间（毫秒） */
    private long cascadeTimeout = 30000L;

    private Boolean enableFirstPacketDetect = true;

    @Data
    public static class ModelCandidate {
        /** 模型唯一标识，如 "qwen-max", "qwen-turbo" */
        private String name;

        /** 模型显示名称 */
        private String displayName;

        /** API实际模型名 */
        private String apiModel;

        /** 优先级（1最高） */
        private int priority = 5;

        /** 是否启用 */
        private boolean enabled = true;

        /** 熔断器配置 */
        private CircuitBreakerConfig circuitBreaker = new CircuitBreakerConfig();

        /** 权重（用于负载均衡策略） */
        private int weight = 1;
    }

    @Data
    public static class CircuitBreakerConfig {
        private int failureRateThreshold = 50;
        private long waitDurationInOpenState = 10000L;
        private int slidingWindowSize = 10;
        private int minimumNumberOfCalls = 5;
    }

    /**
     * 根据名字找模型
     * @param name
     * @return
     */
    public ModelCandidate findByName(String name) {
        return candidates.stream()
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * 获取所有启用的模型
     */
    public List<ModelCandidate> getEnabledCandidates() {
        return candidates.stream()
                .filter(ModelCandidate::isEnabled)
                .toList();
    }
}