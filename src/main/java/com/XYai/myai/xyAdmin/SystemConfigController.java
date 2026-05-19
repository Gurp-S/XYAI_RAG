package com.XYai.myai.xyAdmin;

import com.XYai.myai.config.Result;
import com.XYai.myai.xyAdmin.service.SystemConfigService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 持久化系统配置管理
 * 存储功能-模型分配、管道节点配置等
 */
@Slf4j
@RestController
@RequestMapping("/xyAdmin/system/config")
public class SystemConfigController {

    @Resource
    private SystemConfigService systemConfigService;

    /**
     * 获取所有持久化配置
     */
    @GetMapping
    public Result<Map<String, List<Map<String, Object>>>> getAll() {
        return Result.success(systemConfigService.getAllConfigs());
    }

    /**
     * 保存配置
     */
    @PostMapping("/set")
    public Result<String> setConfig(
            @RequestParam String group,
            @RequestParam String key,
            @RequestParam String value,
            @RequestParam(required = false) String description) {
        systemConfigService.setConfig(group, key, value, description);
        return Result.success("配置已保存");
    }

    /**
     * 获取功能-模型分配
     */
    @GetMapping("/featureModel")
    public Result<Map<String, String>> getFeatureModels() {
        Map<String, String> models = systemConfigService.getGroupConfigs("feature_model");
        return Result.success(models);
    }

    /**
     * 设置功能-模型分配
     */
    @PostMapping("/featureModel")
    public Result<String> setFeatureModel(@RequestParam String feature, @RequestParam String modelName) {
        systemConfigService.setFeatureModel(feature, modelName);
        log.info("管理员设置了功能-模型分配: {} -> {}", feature, modelName);
        return Result.success("功能 [" + feature + "] 的模型已设置为 " + modelName);
    }

    /**
     * 删除配置
     */
    @PostMapping("/delete")
    public Result<String> deleteConfig(@RequestParam String group, @RequestParam String key) {
        systemConfigService.deleteConfig(group, key);
        return Result.success("配置已删除");
    }
}
