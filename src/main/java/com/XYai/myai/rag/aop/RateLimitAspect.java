package com.XYai.myai.rag.aop;


import com.XYai.myai.exception.RateLimitException;
import com.XYai.myai.rag.aop.annotation.RateLimit;
import com.XYai.myai.user.LoginUserInfoManager;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.UUID;

/**
 * 限流切面：在方法执行前通过 Redis + Lua 完成固定窗口限流判断。
 * 限流 key 默认按「资源名 + 用户/IP」隔离，避免全局一刀切。
 */
@Aspect
@Component
public class RateLimitAspect {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedisScript<Boolean> redisScript;

    /**
     * 匹配所有使用 {@link RateLimit} 注解的方法。
     */
    @Pointcut("@annotation(com.XYai.myai.rag.aop.annotation.RateLimit)")
    public void pointCut() {
    }

    /**
     * 在目标方法执行前进行限流检查，超过阈值时抛出限流异常。
     *
     * @param rateLimit 方法上的限流注解参数
     */
    @Before("pointCut() && @annotation(rateLimit)")
    public void before(RateLimit rateLimit) {
        int limit = rateLimit.limit();
        String name = rateLimit.rateName();
        if (!StringUtils.hasText(name)) {
            name = "default";
        }
        long windowMs = rateLimit.windowMs();
        String bucketKey = "rate:" + name + ":" + resolveCallerKey();

        Boolean isAccess = stringRedisTemplate.execute(
                redisScript,
                Collections.singletonList(bucketKey),
                String.valueOf(limit),
                String.valueOf(windowMs),
                UUID.randomUUID().toString()
        );
        if (Boolean.FALSE.equals(isAccess)) {
            throw new RateLimitException();
        }
    }

    private String resolveCallerKey() {
        Long userId = LoginUserInfoManager.getUserId();
        if (userId != null) {
            return "u:" + userId;
        }
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StringUtils.hasText(forwarded)) {
                return "ip:" + forwarded.split(",")[0].trim();
            }
            return "ip:" + request.getRemoteAddr();
        }
        return "anon";
    }
}
