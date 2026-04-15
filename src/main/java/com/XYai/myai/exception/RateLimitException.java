package com.XYai.myai.exception;

/**
 * 自定义限流异常，用于在限流触发时抛出。
 */
public class RateLimitException extends RuntimeException {

    /**
     * 使用默认错误信息创建限流异常。
     */
    public RateLimitException() {
        super("rate limit exceeded");
    }

    /**
     * 使用自定义消息创建限流异常。
     *
     * @param message 异常消息
     */
    public RateLimitException(String message) {
        super(message);
    }

}

