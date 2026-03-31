package com.XYai.myai.Exception;

/**
 * 工具调用执行异常。
 */
public class ToolExecutionException extends RuntimeException {

    /**
     * 创建带原因的工具执行异常。
     *
     * @param msg 异常消息
     * @param cause 原始异常
     */
    public ToolExecutionException(String msg, Throwable cause) {
        super(msg, cause);
    }

    /**
     * 创建仅带消息的工具执行异常。
     *
     * @param msg 异常消息
     */
    public ToolExecutionException(String msg) {
        super(msg);
    }
}

