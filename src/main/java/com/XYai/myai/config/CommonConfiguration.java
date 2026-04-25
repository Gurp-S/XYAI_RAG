package com.XYai.myai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 应用通用配置，包含 Ollama 模型与结构化输出格式定义。
 */
@Configuration
@EnableAsync
public class CommonConfiguration {

    /**
     * 定义专门用于记忆压缩和大模型异步调用的线程池，防止阻塞主业务。
     */
    @Bean("memoryCompactExecutor")
    public Executor memoryCompactExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("MemCompact-");
        // 如果队列塞满则由调用者当前线程执行（防止任务丢失）
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 加载 Redis 限流 Lua 脚本。
     *
     * @return Lua 脚本执行对象，返回类型为 Boolean
     */
    @Bean
    public RedisScript<Boolean> loadRedisScript() {
        DefaultRedisScript<Boolean> redisLimitScript = new DefaultRedisScript<>();
        //lua脚本路径
        redisLimitScript.setLocation(new ClassPathResource("luaScript/limit.lua"));
        //lua脚本返回值
        redisLimitScript.setResultType(java.lang.Boolean.class);
        return redisLimitScript;
    }
}