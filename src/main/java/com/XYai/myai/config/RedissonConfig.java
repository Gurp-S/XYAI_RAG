package com.XYai.myai.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 配置类。
 * 用于配置 RedissonClient 客户端，以便在 Spring 应用中使用分布式锁等 Redisson 功能。
 */
@Configuration
public class RedissonConfig {

    /**
     * Redisson 客户端配置，基于 Spring 的属性注入（spring.redis.*），提供 RedissonClient Bean。
     */

    @Value("${spring.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        // 创建 RedissonClient 并在容器销毁时关闭
        Config config = new Config();
        String addr = "redis://" + redisHost + ":" + redisPort;
        config.useSingleServer().setAddress(addr);
        if (redisPassword != null && !redisPassword.isBlank()) {
            config.useSingleServer().setPassword(redisPassword);
        }
        return Redisson.create(config);
    }
}
