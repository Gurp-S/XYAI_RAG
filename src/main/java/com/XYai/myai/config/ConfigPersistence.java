package com.XYai.myai.config;

import com.XYai.myai.xyAdmin.service.SystemConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 通用配置持久化助手。
 * 将任意配置对象的全部字段序列化为 JSON，存入 SystemConfigService（xy_system_config 表）。
 * 启动时从 DB 加载并覆盖到对象上，使运行态变更在重启后不丢失。
 */
@Slf4j
@Component
public class ConfigPersistence {

    @Resource
    private SystemConfigService systemConfigService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 将配置对象保存到 DB（每个分组只存一条 "config" key，值为全字段 JSON）。
     */
    public void save(String group, Object configBean) {
        try {
            Map<String, Object> map = objectMapper.convertValue(configBean,
                    new TypeReference<Map<String, Object>>() {});
            // 剔除 null 值以及 Jackson 注入的元字段
            map.entrySet().removeIf(e -> e.getValue() == null || "class".equals(e.getKey()));
            String json = objectMapper.writeValueAsString(map);
            systemConfigService.setConfig(group, "config", json, null);
            log.debug("配置已持久化: group={}", group);
        } catch (Exception e) {
            log.warn("配置持久化失败: group={}", group, e);
        }
    }

    /**
     * 从 DB 加载配置并覆盖到目标对象上。
     * DB 中没有数据时静默跳过（继续使用 yaml 或代码默认值）。
     */
    public void load(String group, Object configBean) {
        String json = systemConfigService.getConfig(group, "config");
        if (json == null || json.isBlank()) {
            log.debug("DB 中无持久化配置，使用默认值: group={}", group);
            return;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            objectMapper.updateValue(configBean, map);
            log.info("已从 DB 加载持久化配置: group={}", group);
        } catch (Exception e) {
            log.warn("加载持久化配置失败: group={}", group, e);
        }
    }
}
