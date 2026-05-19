package com.XYai.myai.rag.chat;

import com.XYai.myai.rag.chat.pojo.StreamResult;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 模型路由服务接口
 * 提供多模型调用、自动降级、流式响应等功能
 */
public interface ModelRouterService {

    /**
     * 普通调用（自动降级）
     * @param prompt 用户输入
     * @param sessionId 会话ID
     * @return 模型响应
     */
    String route(String prompt, String sessionId);

    /**
     * 指定首选模型（失败自动降级）
     * @param prompt 用户输入
     * @param preferredModel 首选模型名称
     * @param sessionId 会话ID
     * @return 模型响应
     */
    String routeWithPreferred(String prompt, String preferredModel, String sessionId);

    /**
     * 快速模式（并发调用，取最快响应）
     * @param prompt 用户输入
     * @param sessionId 会话ID
     * @return 模型响应
     */
    String routeFast(String prompt, String sessionId);

    /**
     * 流式调用（自动降级）
     * @param prompt 用户输入
     * @param sessionId 会话ID
     * @return 模型响应流
     */
    StreamResult routeStream(String prompt, String sessionId);

    /**
     * 指定首选模型流式调用（失败自动降级）
     * @param prompt 用户输入
     * @param preferredModel 首选模型名称
     * @param sessionId 会话ID
     * @return 模型响应流
     */
    StreamResult routeWithPreferredStream(String prompt, String preferredModel, String sessionId);

    /**
     * 快速模式流式调用（并发调用，取最快响应）
     * @param prompt 用户输入
     * @param sessionId 会话ID
     * @return 模型响应流
     */
    StreamResult routeFastStream(String prompt, String sessionId);

    /**
     * 获取所有模型的健康状态
     * @return 健康状态Map
     */
    Map<String, Object> getHealthStatus();

    /**
     * 重置指定模型的状态
     * @param modelName 模型名称
     */
    void resetModel(String modelName);
}