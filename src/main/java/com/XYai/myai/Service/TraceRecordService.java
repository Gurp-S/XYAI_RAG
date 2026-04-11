package com.XYai.myai.Service;

/**
 * 链路追踪记录服务接口。
 * 定义了记录执行链路、节点执行情况以及报错信息的相关能力。
 */
public interface TraceRecordService {

    /**
     * 记录节点报错信息
     */
    void recordNodeError(String traceId, String nodeId, String message);

    /**
     * 记录整个链路级别报错信息
     */
    void recordError(String traceId, String message);

    /**
     * 开始记录新的一条执行链路
     */
    void startRun(String traceId, String name);

    /**
     * 记录当前节点的执行情况
     */
    void recordNode(String traceId, String nodeId, Object name, Object type, long costTime);


}
