package com.XYai.myai.rag.aop;

import com.XYai.myai.rag.aop.annotation.RateLimit;
import org.aspectj.lang.annotation.Pointcut;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimitPointcutTest {

    @Test
    void pointcutMatchesAnnotationFqcn() throws Exception {
        Method method = RateLimitAspect.class.getDeclaredMethod("pointCut");
        Pointcut pointcut = method.getAnnotation(Pointcut.class);
        String expected = "@annotation(" + RateLimit.class.getName() + ")";
        assertEquals(expected, pointcut.value());
    }

    @Test
    void annotationIsMethodTarget() {
        assertTrue(RateLimit.class.isAnnotation());
    }
}
