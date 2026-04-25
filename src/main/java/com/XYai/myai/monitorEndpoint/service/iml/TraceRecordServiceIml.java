package com.XYai.myai.monitorEndpoint.service.iml;

import com.XYai.myai.mapper.NodeRecordMapper;
import com.XYai.myai.mapper.TraceRecordMapper;
import com.XYai.myai.monitorEndpoint.service.TraceRecordService;
import com.XYai.myai.rag.aop.Annotation.NodeRecord;
import com.XYai.myai.rag.aop.Annotation.TraceRecord;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 链路追踪记录服务接口
 * 步骤：
 * 1. 使用 @Service 注解该实现类。
 * 2. 注入数据库的 Mapper（如 TraceRecordMapper、NodeRecordMapper），或注入 RedisTemplate。
 * 3. 实现以下各个方法，在方法中：
 * - 构造一个包含对应数据的实体实体对象 (Entity)。
 * - 调用 Mapper 进行 `insert()` 或 `update()` 保存数据。
 * - 注意：记录日志的方法建议使用 @Async 异步化，以避免阻塞主干业务。
 */
@Slf4j
@Service
public class TraceRecordServiceIml implements TraceRecordService {

    private static final String TRACE_KEY_PREFIX = "Trace:";
    private static final String CURRENT_NODE_TYPE_SUFFIX = ":currentNodeType";

    @Resource
    private TraceRecordMapper traceRecordMapper;

    @Resource
    private NodeRecordMapper nodeRecordMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 记录节点报错信息
     */
    @Override
    @Async
    public void recordNodeError(String traceId, String nodeId, String message) {
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
    }

    /**
     * 记录整个链路级别报错信息
     */
    @Override
    @Async
    public void recordError(String traceId, String message) {
        try {
            LambdaUpdateWrapper<TraceRecord> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(TraceRecord::getTraceId, traceId)
                    .set(TraceRecord::getStatus, "ERROR")
                    .set(TraceRecord::getErrorMessage, message);
            traceRecordMapper.update(null, updateWrapper);

            TraceRecord record = TraceRecord.builder()
                    .traceId(traceId)
                    .status("ERROR")
                    .errorMessage(message)
                    .build();
            stringRedisTemplate.opsForValue().set(TRACE_KEY_PREFIX + traceId, JSON.toJSONString(record));
        } catch (Exception e) {
            log.error("Failed to record trace error for traceId: {}", traceId, e);
        }
    }

    /**
     * 开始记录新的一条执行链路
     */
    @Override
    @Async
    public void startRun(String traceId, String name) {
        try {
            TraceRecord record = TraceRecord.builder()
                    .traceId(traceId)
                    .name(name)
                    .startTime(System.currentTimeMillis())
                    .status("RUNNING")
                    .build();
            stringRedisTemplate.opsForValue().set(TRACE_KEY_PREFIX + traceId, JSON.toJSONString(record));
            traceRecordMapper.insert(record);
        } catch (Exception e) {
            log.error("Failed to start run for traceId: {}", traceId, e);
        }
    }

    /**
     * 记录当前节点的执行情况
     */
    @Override
    @Async
    public void recordNode(String traceId, String nodeId, Object name, Object type, long costTime) {
        try {
            NodeRecord record = NodeRecord.builder()
                    .traceId(traceId)
                    .nodeId(nodeId)
                    .nodeName(name != null ? name.toString() : null)
                    .nodeType(type != null ? type.toString() : null)
                    .costTime(costTime)
                    .status("SUCCESS")
                    .build();
            stringRedisTemplate.opsForValue().set(TRACE_KEY_PREFIX + traceId + CURRENT_NODE_TYPE_SUFFIX,
                    type != null ? type.toString() : "unknown");
            nodeRecordMapper.insert(record);
        } catch (Exception e) {
            log.error("Failed to record node for traceId: {}, nodeId: {}", traceId, nodeId, e);
        }
    }
}
