package com.XYai.myai.rag.chat.pojo;

import com.XYai.myai.rag.chat.ModelRegistryService;
import com.XYai.myai.xyAdmin.service.SystemConfigService;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class ModelRouterProperties {

    private final ModelRegistryService registryService;
    private final SystemConfigService systemConfigService;

    public ModelRouterProperties(ModelRegistryService registryService,
            SystemConfigService systemConfigService) {
        this.registryService = registryService;
        this.systemConfigService = systemConfigService;
    }

    /** 获取全部模型列表（按优先级排序） */
    public List<ModelCandidateEntity> getCandidates() {
        return registryService.listAll();
    }

    /** 根据名称查找模型 */
    public ModelCandidateEntity findByName(String name) {
        return registryService.findByName(name);
    }

    /** 获取所有启用的模型 */
    public List<ModelCandidateEntity> getEnabledCandidates() {
        return registryService.listAll().stream()
                .filter(ModelCandidateEntity::isEnabled)
                .toList();
    }

    // ==================== 功能-模型分配 ====================

    /** 获取指定功能使用的模型名（通过 feature_model 分组） */
    public String getFeatureModel(String feature) {
        return systemConfigService.getFeatureModel(feature);
    }

    /** 设置指定功能使用的模型名 */
    public void setFeatureModel(String feature, String modelName) {
        systemConfigService.setFeatureModel(feature, modelName);
    }

    /** 获取所有功能-模型分配 */
    public Map<String, String> getFeatureModels() {
        return systemConfigService.getGroupConfigs("feature_model");
    }

    // ==================== 路由策略（从 environment 读取，兼容 yaml） ====================

    public String getStrategy() {
        return Environment.class.getName(); // 占位，实际由调用方决定
    }

    public long getFirstPacketTimeout() {
        return 5000;
    }

    public long getCascadeTimeout() {
        return 30000;
    }

    public boolean isEnableFirstPacketDetect() {
        return true;
    }
}