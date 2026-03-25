package com.XYai.myai.Aop;


import com.XYai.myai.Annotation.rateLimit;
import com.XYai.myai.Exception.rateLimitException;
import jakarta.annotation.Resource;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.UUID;

/**
 * 限流切面：在方法执行前通过 Redis + Lua 完成固定窗口限流判断。
 */
@Aspect
@Component
public class rateLimitAspect {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisScript<Boolean> redisScript;

    /**
     * 匹配所有使用 {@link rateLimit} 注解的方法。
     */
    @Pointcut("@annotation(com.XYai.myai.Annotation.rateLimit)")
    public void pointCut(){}

    /**
     * 在目标方法执行前进行限流检查，超过阈值时抛出限流异常。
     *
     * @param rateLimit 方法上的限流注解参数
     */
    @Before("pointCut() && @annotation(rateLimit)")
    public void before(rateLimit rateLimit) {
        // 从注解中读取限流参数。
        int limit = rateLimit.limit();
        String name = rateLimit.rateName();
        // 当前时间戳。
        long now = System.currentTimeMillis();

        // 执行 Lua 脚本进行原子限流判断。
        Boolean isAccess = stringRedisTemplate.execute(
                redisScript,
                Collections.singletonList(name),
                String.valueOf(limit),
                String.valueOf(now - 1000),
                // 限流窗口的右区间。
                String.valueOf(now),
                // zset member 唯一值，避免同值覆盖。
                UUID.randomUUID().toString()
        );

        if (!Boolean.TRUE.equals(isAccess)) {
            throw new rateLimitException();
        }
    }
}
