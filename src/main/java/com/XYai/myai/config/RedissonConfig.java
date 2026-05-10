package com.XYai.myai.config;

import io.lettuce.core.RedisURI;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.redis.lettucemod.RedisModulesClient;
import com.redis.lettucemod.api.sync.RedisModulesCommands;

/**
 * Redisson + Redis 模块配置
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host:127.0.0.1}")
    private String redisHost;

    @Value("${spring.redis.port:6379}")
    private int redisPort;

    @Value("${spring.redis.password:}")
    private String redisPassword;

    /**
     * Redisson 客户端（分布式锁）
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.setCodec(new StringCodec());
        String addr = "redis://" + redisHost + ":" + redisPort;
        config.useSingleServer().setAddress(addr);
        if (redisPassword != null && !redisPassword.isBlank()) {
            config.useSingleServer().setPassword(redisPassword);
        }
        return Redisson.create(config);
    }

    /**
     * Redis 模块客户端（支持 RedisGraph / RedisSearch 等）
     * 替换原来错误的 RedisGraphCommands
     */
    @Bean
    public RedisModulesCommands<String, String> redisModulesCommands() {
        RedisURI uri = RedisURI.Builder.redis(redisHost, redisPort).build();
        RedisModulesClient client = RedisModulesClient.create(uri);
        return client.connect().sync();
    }
}