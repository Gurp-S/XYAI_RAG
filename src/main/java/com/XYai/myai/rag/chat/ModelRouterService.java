package com.XYai.myai.rag.chat;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 模型路由服务接口
 * 提供多模型调用、自动降级、流式响应（回调模式）等功能
 */
public interface ModelRouterService {

    /**
     * 普通调用（自动降级）
     */
    String route(String prompt, Long sessionId);

    /**
     * 指定首选模型（失败自动降级）
     */
    String routeWithPreferred(String prompt, String preferredModel, Long sessionId);

    /**
     * 快速模式（并发调用，取最快响应）
     */
    String routeFast(String prompt, Long sessionId);

    /**
     * 流式调用（自动降级）
     *
     * @return 实际使用的模型名称
     */
    String routeStream(String prompt, Long sessionId,
                       Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete);

    /**
     * 指定首选模型流式调用（失败自动降级）
     *
     * @return 实际使用的模型名称
     */
    String routeWithPreferredStream(String prompt, String preferredModel, Long sessionId,
                                    Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete);

    /**
     * 快速模式流式调用
     *
     * @return 实际使用的模型名称
     */
    String routeFastStream(String prompt, Long sessionId,
                           Consumer<String> onChunk, Consumer<Throwable> onError, Runnable onComplete);

    /**
     * 获取所有模型的健康状态
     */
    Map<String, Object> getHealthStatus();

    /**
     * 重置指定模型的状态
     */
    void resetModel(String modelName);
}
