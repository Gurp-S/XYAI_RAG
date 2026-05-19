package com.XYai.myai.rag.mcp;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * MCP 工具运行时开关注册表。
 * 使用 Caffeine 本地缓存 + Redis 持久化，重启后状态不丢失。
 */
@Slf4j
@Component
public class McpToolToggleRegistry {

    private static final String REDIS_KEY = "xyai:mcp:toggle:disabled";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final Cache<String, Boolean> toggleCache = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(200)
            .build();

    @PostConstruct
    public void init() {
        // 启动时从 Redis 恢复禁用状态
        try {
            Set<String> stored = stringRedisTemplate.opsForSet().members(REDIS_KEY);
            if (stored != null && !stored.isEmpty()) {
                stored.forEach(name -> toggleCache.put(name, Boolean.FALSE));
                log.info("[MCP_TOGGLE] 从 Redis 恢复了 {} 个禁用工具", stored.size());
            }
        } catch (Exception e) {
            log.warn("[MCP_TOGGLE] 无法从 Redis 恢复状态: {}", e.getMessage());
        }
    }

    /**
     * 禁用指定工具
     */
    public void disable(String toolName) {
        toggleCache.put(toolName, Boolean.FALSE);
        try {
            stringRedisTemplate.opsForSet().add(REDIS_KEY, toolName);
        } catch (Exception e) {
            log.warn("[MCP_TOGGLE] Redis 持久化失败: {}", e.getMessage());
        }
        log.info("[MCP_TOGGLE] 工具已禁用: {}", toolName);
    }

    /**
     * 启用指定工具
     */
    public void enable(String toolName) {
        toggleCache.put(toolName, Boolean.TRUE);
        try {
            stringRedisTemplate.opsForSet().remove(REDIS_KEY, toolName);
        } catch (Exception e) {
            log.warn("[MCP_TOGGLE] Redis 持久化失败: {}", e.getMessage());
        }
        log.info("[MCP_TOGGLE] 工具已启用: {}", toolName);
    }

    /**
     * 检查工具是否已启用
     */
    public boolean isEnabled(String toolName) {
        Boolean val = toggleCache.getIfPresent(toolName);
        // 如果在缓存中，检查值；不在缓存中默认启用
        if (val != null)
            return val;
        // 不在缓存中时查 Redis
        try {
            Boolean isMember = stringRedisTemplate.opsForSet().isMember(REDIS_KEY, toolName);
            if (Boolean.TRUE.equals(isMember)) {
                toggleCache.put(toolName, Boolean.FALSE);
                return false;
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    /**
     * 获取所有已禁用的工具名称
     */
    public Set<String> getDisabledTools() {
        Set<String> result = new HashSet<>();
        try {
            Set<String> stored = stringRedisTemplate.opsForSet().members(REDIS_KEY);
            if (stored != null)
                result.addAll(stored);
        } catch (Exception ignored) {
        }
        return Collections.unmodifiableSet(result);
    }

    /**
     * 获取所有已启用的工具名称（从全集中过滤）
     */
    public Set<String> filterEnabled(Set<String> allToolNames) {
        Set<String> result = new HashSet<>(allToolNames);
        result.removeAll(getDisabledTools());
        return result;
    }
}
