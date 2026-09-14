package com.XYai.myai.monitorEndpoint.service;

/**
 * 链路追踪记录服务接口。
 * 定义了记录执行链路、节点执行情况以及报错信息的相关能力。
 */
public interface TraceRecordService {

    /**
     * 统一更新链路执行状态（合并 finishRun / recordError / recordRunWarn）
     * @param traceId   链路ID
     * @param status    SUCCESS / ERROR / WARN
     * @param message   错误或警告消息（可为null）
     * @param costTimeMs 执行耗时（毫秒，可为null）
     */
    void updateRun(String traceId, String status, String message, Long costTimeMs);

    /**
     * 记录节点报错信息
     */
    void recordNodeError(String traceId, String nodeId, String message);


    void finishRun(String traceId,long startTimeMs);

    /**
     * 记录整个链路级别报错信息
     */
    void recordError(String traceId, String message);


    void recordNode(String traceId, String nodeId, Object name, Object type);

    /**
     * 开始记录新的一条执行链路
     */
    void startRun(String traceId, String name);

    /**
     * 记录当前节点的执行情况
     */
    void updateNode(String traceId,String status, String nodeId, Object name, Object type, long costTime, String message);

    // 在 TraceRecordService 接口或实现类中添加
    void recordNodeWarn(String traceId, String nodeId, String warnMessage, long costTime);

    void recordRunWarn(String traceId, String warnMessage, long costTime);
}