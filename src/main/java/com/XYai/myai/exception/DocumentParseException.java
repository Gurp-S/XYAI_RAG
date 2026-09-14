package com.XYai.myai.exception;

/**
 * 自定义文档解析异常，便于上层统一处理。
 */
public class DocumentParseException extends RuntimeException {
    public DocumentParseException(String message) {
        super(message);
    }

    public DocumentParseException(String message, Throwable cause) {
        super(message, cause);
    }
}