package com.XYai.myai.rag.aop;


import com.XYai.myai.exception.RateLimitException;
import com.XYai.myai.rag.aop.Annotation.rateLimit;
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
    @Pointcut("@annotation(com.XYai.myai.rag.aop.Annotation.rateLimit)")
    public void pointCut() {
    }

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
        long windowMs = rateLimit.windowMs();

        // 执行 Lua 脚本进行原子限流判断。
        Boolean isAccess = stringRedisTemplate.execute(
                redisScript,
                Collections.singletonList(name),
                String.valueOf(limit),
                // 仅传窗口大小，让Redis自己查权威时间
                String.valueOf(windowMs),
                // Zset member 唯一值，避免同值覆盖。
                UUID.randomUUID().toString()
        );
        if (!isAccess) {
            throw new RateLimitException();
        }
    }
}
