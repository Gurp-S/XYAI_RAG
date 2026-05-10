package com.XYai.myai.rag.chat;

import com.XYai.myai.rag.chat.pojo.ModelRouterProperties;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 模型选择器
 * 负责根据健康状态、优先级等选择可用模型
 */
@Slf4j
@Data
@Component
public class ModelSelector {

    @Resource
    private ModelRouterProperties routerProperties;

    @Resource
    private ModelHealthStore healthStore;

    /**
     * 路由策略
     */
    public enum Strategy {
        PRIORITY,      // 优先级优先（默认）
        HEALTH_FIRST,  // 健康优先
        ROUND_ROBIN    // 轮询
    }

    /**
     * 获取所有可用模型（健康 + 启用）
     */
    public List<ModelRouterProperties.ModelCandidate> getAvailableModels() {
        return routerProperties.getCandidates().stream()
                .filter(ModelRouterProperties.ModelCandidate::isEnabled)
                .filter(candidate -> healthStore.isHealthy(candidate.getName()))
                .sorted(Comparator.comparingInt(ModelRouterProperties.ModelCandidate::getPriority))
                .collect(Collectors.toList());
    }

    /**
     * 获取降级链路（按优先级顺序的模型名称列表）
     */
    public List<String> getFallbackChain() {
        return getAvailableModels().stream()
                .map(ModelRouterProperties.ModelCandidate::getName)
                .collect(Collectors.toList());
    }

    /**
     * 选择最优模型
     */
    public String selectBestModel(Strategy strategy) {
        List<ModelRouterProperties.ModelCandidate> candidates = getAvailableModels();

        if (candidates.isEmpty()) {
            log.warn("没有可用的模型，返回第一个启用的模型");
            return getFirstEnabledModel();
        }

        return switch (strategy) {
            case HEALTH_FIRST -> healthFirstSelect(candidates);
            case ROUND_ROBIN -> roundRobinSelect(candidates);
            default -> prioritySelect(candidates);
        };
    }

    /**
     * 优先级策略：返回优先级最高的
     */
    private String prioritySelect(List<ModelRouterProperties.ModelCandidate> candidates) {
        if (candidates.isEmpty()) return getFirstEnabledModel();
        return candidates.getFirst().getName();
    }

    /**
     * 健康优先策略：返回成功率最高的
     */
    private String healthFirstSelect(List<ModelRouterProperties.ModelCandidate> candidates) {
        return candidates.stream()
                .max(Comparator.comparingDouble(c -> healthStore.getSuccessRate(c.getName())))
                .map(ModelRouterProperties.ModelCandidate::getName)
                .orElse(getFirstEnabledModel());
    }

    /**
     * 轮询策略
     */
    private String roundRobinSelect(List<ModelRouterProperties.ModelCandidate> candidates) {
        if (candidates.isEmpty()) return getFirstEnabledModel();
        int index = (int) (System.currentTimeMillis() / 1000 % candidates.size());
        return candidates.get(index).getName();
    }

    /**
     * 获取第一个启用的模型
     */
    private String getFirstEnabledModel() {
        return routerProperties.getCandidates().stream()
                .filter(ModelRouterProperties.ModelCandidate::isEnabled)
                .findFirst()
                .map(ModelRouterProperties.ModelCandidate::getName)
                .orElse("qwen-turbo");
    }

    /**
     * 获取模型配置
     */
    public ModelRouterProperties.ModelCandidate getCandidateByName(String name) {
        return routerProperties.getCandidates().stream()
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .orElse(null);
    }
}