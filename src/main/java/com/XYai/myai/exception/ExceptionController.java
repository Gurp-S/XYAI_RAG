package com.XYai.myai.exception;

import com.XYai.myai.config.Result;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器，统一转换业务异常为接口响应。
 */
@RestControllerAdvice
public class ExceptionController {

    @ExceptionHandler(RateLimitException.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public Result<Void> rateLimitExceptionHandle(RateLimitException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "rate limit exceeded";
        return Result.error(429, msg);
    }
}
