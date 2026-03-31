package com.XYai.myai.Exception;

/**
 * 流水线执行异常。
 */
public class PipelineException extends RuntimeException {

    /**
     * 创建带原因的流水线异常。
     *
     * @param message 异常信息
     * @param cause 原始异常
     */
    public PipelineException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 创建仅带消息的流水线异常。
     *
     * @param message 异常信息
     */
    public PipelineException(String message) {
        super(message);
    }
}

