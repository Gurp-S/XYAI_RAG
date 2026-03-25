package com.XYai.myai.Exception;

import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器，统一转换业务异常为接口响应。
 */
@RestControllerAdvice
public class ExceptionController{

    /**
     * 处理限流异常并返回固定提示。
     *
     * @param e 限流异常
     * @return 限流提示文本
     */
    @ExceptionHandler(rateLimitException.class)
    public String rateLimitExceptionHandle(rateLimitException e) {
        return "rateLimit";
    }
}