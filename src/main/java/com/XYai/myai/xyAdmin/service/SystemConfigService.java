package com.XYai.myai.xyAdmin.service;

import com.XYai.myai.xyAdmin.mapper.SystemConfigMapper;
import com.XYai.myai.xyAdmin.pojo.SystemConfig;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 系统配置持久化服务
 * 负责所有运行时配置的持久化读写（替代仅内存的 Properties）
 * 使用 Caffeine 缓存热点配置，减少 DB 查询
 */
@Slf4j
@Service
public class SystemConfigService {

    @Resource
    private SystemConfigMapper systemConfigMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Caffeine 缓存：group|key → value，5分钟过期 */
    private final Cache<String, String> configCache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    /**
     * 获取配置分组下的所有 KV
     */
    public Map<String, String> getGroupConfigs(String group) {
        List<SystemConfig> list = systemConfigMapper.selectList(
                new QueryWrapper<SystemConfig>().eq("config_group", group));
        Map<String, String> result = new LinkedHashMap<>();
        for (SystemConfig cfg : list) {
            result.put(cfg.getConfigKey(), cfg.getConfigValue());
        }
        return result;
    }

    /**
     * 获取单个配置值（带 Caffeine 缓存）
     */
    public String getConfig(String group, String key) {
        String cacheKey = group + "|" + key;
        String cached = configCache.getIfPresent(cacheKey);
        if (cached != null)
            return cached;

        SystemConfig cfg = systemConfigMapper.selectOne(
                new QueryWrapper<SystemConfig>()
                        .eq("config_group", group)
                        .eq("config_key", key));
        String value = cfg != null ? cfg.getConfigValue() : null;
        if (value != null) {
            configCache.put(cacheKey, value);
        }
        return value;
    }

    /**
     * 获取配置并解析为 Map
     */
    public Map<String, Object> getConfigAsMap(String group, String key) {
        String val = getConfig(group, key);
        if (val == null)
            return Map.of();
        try {
            return objectMapper.readValue(val, new TypeReference<Map<String, Object>>() {
            });
        } catch (JsonProcessingException e) {
            log.warn("解析配置失败: group={}, key={}", group, key, e);
            return Map.of();
        }
    }

    /**
     * 保存配置（写入 DB 并更新缓存）
     */
    public void setConfig(String group, String key, String value, String description) {
        String cacheKey = group + "|" + key;
        SystemConfig existing = systemConfigMapper.selectOne(
                new QueryWrapper<SystemConfig>()
                        .eq("config_group", group)
                        .eq("config_key", key));
        if (existing != null) {
            existing.setConfigValue(value);
            if (description != null)
                existing.setDescription(description);
            existing.setUpdatedAt(LocalDateTime.now());
            systemConfigMapper.updateById(existing);
        } else {
            SystemConfig cfg = SystemConfig.builder()
                    .configGroup(group)
                    .configKey(key)
                    .configValue(value)
                    .description(description)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build();
            systemConfigMapper.insert(cfg);
        }
        configCache.put(cacheKey, value);
        log.debug("系统配置已保存: {}|{} = {}", group, key, value);
    }

    /**
     * 删除配置（同时清理缓存）
     */
    public void deleteConfig(String group, String key) {
        String cacheKey = group + "|" + key;
        systemConfigMapper.delete(
                new QueryWrapper<SystemConfig>()
                        .eq("config_group", group)
                        .eq("config_key", key));
        configCache.invalidate(cacheKey);
    }

    /**
     * 获取所有分组下的配置（含描述）
     */
    public Map<String, List<Map<String, Object>>> getAllConfigs() {
        List<SystemConfig> all = systemConfigMapper.selectList(null);
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (SystemConfig cfg : all) {
            grouped.computeIfAbsent(cfg.getConfigGroup(), k -> new ArrayList<>());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", cfg.getConfigKey());
            item.put("value", cfg.getConfigValue());
            item.put("description", cfg.getDescription());
            item.put("updatedAt", cfg.getUpdatedAt());
            grouped.get(cfg.getConfigGroup()).add(item);
        }
        return grouped;
    }

    /**
     * 保存功能-模型分配
     * feature: mcp_decision / pipeline_enhance / intent_recognition / rewrite /
     * chat_default
     */
    public void setFeatureModel(String feature, String modelName) {
        String desc = switch (feature) {
            case "mcp_decision" -> "MCP工具决策模型";
            case "pipeline_enhance" -> "管道增强模型";
            case "intent_recognition" -> "意图识别模型";
            case "rewrite" -> "查询重写模型";
            case "chat_default" -> "默认对话模型";
            default -> feature;
        };
        setConfig("feature_model", feature, modelName, desc);
    }

    /**
     * 获取功能-模型分配
     */
    public String getFeatureModel(String feature) {
        return getConfig("feature_model", feature);
    }
}
