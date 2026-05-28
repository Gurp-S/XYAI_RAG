package com.XYai.myai.config;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.core.task.TaskExecutor;

import java.util.concurrent.Executor;

/**
 * 应用通用配置，包含 Ollama 模型与结构化输出格式定义。
 */
@Slf4j
@Configuration
@EnableAsync
public class CommonConfiguration implements AsyncConfigurer {

    @Resource
    @Qualifier("taskUserExecutor")
    private TaskExecutor taskUserExecutor;

    @Override
    public Executor getAsyncExecutor() {
        return taskUserExecutor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> log.error("异步方法异常: {}", method, ex);
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