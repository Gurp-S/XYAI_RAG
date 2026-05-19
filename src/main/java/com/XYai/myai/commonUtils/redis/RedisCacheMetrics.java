package com.XYai.myai.commonUtils.redis;

import jakarta.annotation.Resource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Simple Redis-backed metrics helper for cache-related statistics and basic memory inspection.
 */
@Component
public class RedisCacheMetrics {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    public void increment(String metricName) {
        try {
            redisTemplate.opsForValue().increment(RedisKeyConfig.metricsKey(metricName));
        } catch (Exception ignored) {
        }
    }

    public void recordCacheHit(String cacheName) {
        increment("cache_hit:" + cacheName);
    }

    public void recordCacheMiss(String cacheName) {
        increment("cache_miss:" + cacheName);
    }

    /**
     * Returns raw INFO output from Redis server (may be useful for memory inspection).
     */
    public String getRedisInfo() {
        try {
            return redisTemplate.execute((RedisCallback<String>) connection -> {
                try {
                    java.util.Properties props = connection.info();
                    if (props == null) return "";
                    StringBuilder sb = new StringBuilder();
                    for (String name : props.stringPropertyNames()) {
                        sb.append(name).append("=").append(props.getProperty(name)).append('\n');
                    }
                    return sb.toString();
                } catch (Exception ex) {
                    return "";
                }
            });
        } catch (Exception e) {
            return "";
        }
    }
}


