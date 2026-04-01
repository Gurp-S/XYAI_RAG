package com.XYai.myai.RAG.Aop.Annotation;

import java.lang.annotation.*;

/**
 * 接口限流注解。
 *
 * <p>用于标记需要按固定窗口限流的方法，具体限流逻辑由 AOP 切面执行。</p>
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface rateLimit{

    /**
     * 限流阈值，表示时间窗口内允许的最大请求次数。
     */
    int limit();

    /**
     * 限流资源标识（例如接口名或业务名）。
     */
    String rateName() default "";

    /**
     * 窗口大小（毫秒），默认1秒。
     */
    long windowMs() default 1000;
}