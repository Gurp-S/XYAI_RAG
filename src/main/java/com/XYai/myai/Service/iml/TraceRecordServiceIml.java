package com.XYai.myai.Service.iml;

import com.XYai.myai.Service.TraceRecordService;
import com.XYai.myai.Annotation.NodeRecord;
import com.XYai.myai.Annotation.TraceRecord;
import com.XYai.myai.mapper.NodeRecordMapper;
import com.XYai.myai.mapper.TraceRecordMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * 链路追踪记录服务接口
 * 步骤：
 * 1. 使用 @Service 注解该实现类。
 * 2. 注入数据库的 Mapper（如 TraceRecordMapper、NodeRecordMapper），或注入 RedisTemplate。
 * 3. 实现以下各个方法，在方法中：
 *    - 构造一个包含对应数据的实体实体对象 (Entity)。
 *    - 调用 Mapper 进行 `insert()` 或 `update()` 保存数据。
 *    - 注意：记录日志的方法建议使用 @Async 异步化，或者在方法内部通过 CompletableFuture 异步入库，以避免阻塞主干业务。
 */
@Slf4j
@Service
public class TraceRecordServiceIml implements TraceRecordService {

    @Resource
    private TraceRecordMapper traceRecordMapper;

    @Resource
    private NodeRecordMapper nodeRecordMapper;

    /**
     * 记录节点报错信息
     */
    @Override
    @Async
    public void recordNodeError(String traceId, String nodeId, String message) {
        CompletableFuture.runAsync(() -> {
            try {
                LambdaUpdateWrapper<NodeRecord> updateWrapper = new LambdaUpdateWrapper<>();
                updateWrapper.eq(NodeRecord::getTraceId, traceId)
                        .eq(NodeRecord::getNodeId, nodeId)
                        .set(NodeRecord::getStatus, "ERROR")
                        .set(NodeRecord::getErrorMessage, message);
                nodeRecordMapper.update(null, updateWrapper);
            } catch (Exception e) {
                log.error("Failed to record node error for traceId: {}, nodeId: {}", traceId, nodeId, e);
            }
        });
    }

    /**
     * 记录整个链路级别报错信息
     */
    @Override
    @Async
    public void recordError(String traceId, String message) {
        CompletableFuture.runAsync(() -> {
            try {
                LambdaUpdateWrapper<TraceRecord> updateWrapper = new LambdaUpdateWrapper<>();
                updateWrapper.eq(TraceRecord::getTraceId, traceId)
                        .set(TraceRecord::getStatus, "ERROR")
                        .set(TraceRecord::getErrorMessage, message);
                traceRecordMapper.update(null, updateWrapper);
            } catch (Exception e) {
                log.error("Failed to record trace error for traceId: {}", traceId, e);
            }
        });
    }

    /**
     * 开始记录新的一条执行链路
     */
    @Override
    @Async
    public void startRun(String traceId, String name) {
        CompletableFuture.runAsync(() -> {
            try {
                TraceRecord record = TraceRecord.builder()
                        .traceId(traceId)
                        .name(name)
                        .startTime(System.currentTimeMillis())
                        .status("RUNNING")
                        .build();
                traceRecordMapper.insert(record);
            } catch (Exception e) {
                log.error("Failed to start run for traceId: {}", traceId, e);
            }
        });
    }

    /**
     * 记录当前节点的执行情况
     */
    @Override
    @Async
    public void recordNode(String traceId, String nodeId, Object name, Object type, long costTime) {
        CompletableFuture.runAsync(() -> {
            try {
                NodeRecord record = NodeRecord.builder()
                        .traceId(traceId)
                        .nodeId(nodeId)
                        .nodeName(name != null ? name.toString() : null)
                        .nodeType(type != null ? type.toString() : null)
                        .costTime(costTime)
                        .status("SUCCESS")
                        .build();
                nodeRecordMapper.insert(record);
            } catch (Exception e) {
                log.error("Failed to record node for traceId: {}, nodeId: {}", traceId, nodeId, e);
            }
        });
    }
}
