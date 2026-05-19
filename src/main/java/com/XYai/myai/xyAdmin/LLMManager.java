package com.XYai.myai.xyAdmin;

import com.XYai.myai.config.Result;
import com.XYai.myai.rag.chat.ModelHealthStore;
import com.XYai.myai.rag.chat.ModelRegistryService;
import com.XYai.myai.rag.chat.pojo.ModelCandidateEntity;
import com.XYai.myai.rag.chat.pojo.ModelRouterProperties;
import com.XYai.myai.xyAdmin.service.SystemConfigService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/xyAdmin/llm")
public class LLMManager {

    @Resource
    private ModelRegistryService registryService;

    @Resource
    private ModelHealthStore healthStore;

    @Resource
    private ModelRouterProperties routerProperties;

    @Resource
    private SystemConfigService systemConfigService;

    // ======================== 模型 CRUD ========================

    /** 获取全部模型列表 */
    @GetMapping("/candidates")
    public Result<List<ModelCandidateEntity>> list() {
        return Result.success(registryService.listAll());
    }

    /** 新增模型（含 priority 挤占） */
    @PostMapping("/add")
    public Result<String> add(@RequestParam String name,
            @RequestParam String displayName,
            @RequestParam String apiModel,
            @RequestParam(defaultValue = "5") int priority,
            @RequestParam(defaultValue = "0.3") double temperature,
            @RequestParam(defaultValue = "2000") int maxTokens) {
        try {
            ModelCandidateEntity entity = new ModelCandidateEntity();
            entity.setName(name);
            entity.setDisplayName(displayName);
            entity.setApiModel(apiModel);
            entity.setPriority(priority);
            entity.setEnabled(true);
            entity.setTemperature(temperature);
            entity.setMaxTokens(maxTokens);
            registryService.add(entity);
            healthStore.registerModel(name);
            return Result.success("模型 [" + name + "] 已添加");
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    /** 删除模型 */
    @PostMapping("/delete")
    public Result<String> delete(@RequestParam String name) {
        registryService.remove(name);
        healthStore.reset(name);
        return Result.success("模型 [" + name + "] 已删除");
    }

    /** 启停/更新模型 */
    @PostMapping("/update")
    public Result<String> update(@RequestParam String name,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) String apiModel,
            @RequestParam(required = false) Integer priority,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Double temperature,
            @RequestParam(required = false) Integer maxTokens) {
        ModelCandidateEntity entity = registryService.findByName(name);
        if (entity == null)
            return Result.error(404, "模型 [" + name + "] 不存在");
        if (displayName != null)
            entity.setDisplayName(displayName);
        if (apiModel != null)
            entity.setApiModel(apiModel);
        if (priority != null)
            entity.setPriority(priority);
        if (enabled != null)
            entity.setEnabled(enabled);
        if (temperature != null)
            entity.setTemperature(temperature);
        if (maxTokens != null)
            entity.setMaxTokens(maxTokens);

        registryService.update(entity);

        if (Boolean.TRUE.equals(enabled)) {
            healthStore.registerModel(name);
        } else if (Boolean.FALSE.equals(enabled)) {
            healthStore.reset(name);
        }
        return Result.success("模型 [" + name + "] 已更新");
    }

    // ======================== 功能-模型分配 ========================

    /** 获取所有功能-模型分配 */
    @GetMapping("/features")
    public Result<Map<String, String>> getFeatures() {
        return Result.success(routerProperties.getFeatureModels());
    }

    /** 设置指定功能的模型 */
    @PostMapping("/features")
    public Result<String> setFeature(@RequestParam String feature, @RequestParam String modelName) {
        // 校验模型是否存在且已启用
        ModelCandidateEntity entity = registryService.findByName(modelName);
        if (entity == null) {
            return Result.error(404, "模型 [" + modelName + "] 不存在");
        }
        if (!entity.isEnabled()) {
            return Result.error(400, "模型 [" + modelName + "] 已禁用，无法设置为功能模型");
        }
        routerProperties.setFeatureModel(feature, modelName);
        return Result.success("功能 [" + feature + "] → " + modelName);
    }

    // ======================== 健康状态 ========================

    @GetMapping("/health")
    public Result<Object> getHealth(@RequestParam String name) {
        ModelCandidateEntity entity = registryService.findByName(name);
        if (entity == null)
            return Result.error(404, "模型 [" + name + "] 不存在");
        double rawRate = healthStore.getSuccessRate(name);
        double ratePct = Math.round(rawRate * 10000) / 100.0;
        return Result.success(Map.of(
                "name", name,
                "enabled", entity.isEnabled(),
                "healthy", healthStore.isHealthy(name),
                "breakerState", healthStore.getBreakerState(name),
                "successRate", ratePct,
                "avgLatency", Math.round(healthStore.getAvgLatency(name)),
                "totalCalls", healthStore.getTotalCalls(name)));
    }

    /** 批量获取所有模型的健康状态 */
    @GetMapping("/health/all")
    public Result<Map<String, Object>> getAllHealth() {
        return Result.success(healthStore.getAllHealthStatus());
    }

    @PostMapping("/reset")
    public Result<String> reset(@RequestParam String name) {
        healthStore.reset(name);
        return Result.success("模型 [" + name + "] 健康状态已重置");
    }
}