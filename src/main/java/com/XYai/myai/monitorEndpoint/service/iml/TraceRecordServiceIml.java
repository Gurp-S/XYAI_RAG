package com.XYai.myai.monitorEndpoint.service.iml;

import com.XYai.myai.mapper.NodeRecordMapper;
import com.XYai.myai.mapper.TraceRecordMapper;
import com.XYai.myai.monitorEndpoint.service.TraceRecordService;
import com.XYai.myai.rag.aop.annotation.NodeRecord;
import com.XYai.myai.rag.aop.annotation.TraceRecord;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
public class TraceRecordServiceIml implements TraceRecordService {

    @Resource
    private TraceRecordMapper traceRecordMapper;

    @Resource
    private NodeRecordMapper nodeRecordMapper;

    // 定义日期时间格式
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public void startRun(String traceId, String name) {
        try {
            log.info("[TRACE_DB] >>> startRun: traceId={}, name='{}'", traceId, name);

            TraceRecord record = TraceRecord.builder()
                    .traceId(traceId)
                    .name(name)
                    .startTime(LocalDateTime.now()) // 使用 LocalDateTime
                    .status("RUNNING")
                    .build();

            long t1 = System.currentTimeMillis();
            int result = traceRecordMapper.insert(record);
            log.info("[TRACE_DB] <<< startRun 插入成功, result={}, traceId={}, 耗时={}ms",
                    result, traceId, System.currentTimeMillis() - t1);

        } catch (Exception e) {
            log.error("[TRACE_DB] ⚠ startRun 插入失败! traceId={}, name='{}', error={}",
                    traceId, name, e.getMessage(), e);
        }
    }

    @Override
    public void recordNode(String traceId, String nodeId, Object name, Object type, long costTime) {
        try {
            log.info("[TRACE_DB] >>> recordNode: traceId={}, nodeId={}, name='{}', type='{}', costTime={}",
                    traceId, nodeId, name, type, costTime);

            NodeRecord record = NodeRecord.builder()
                    .nodeId(nodeId)
                    .traceId(traceId)
                    .nodeName(name != null ? name.toString() : null)
                    .nodeType(type != null ? type.toString() : null)
                    .costTime(costTime)
                    .status("SUCCESS")
                    .build();

            long t1 = System.currentTimeMillis();
            int result = nodeRecordMapper.updateById(record);
            log.info("[TRACE_DB] <<< recordNode 插入成功, result={}, traceId={}, 耗时={}ms",
                    result, traceId, System.currentTimeMillis() - t1);

        } catch (Exception e) {
            log.error("[TRACE_DB] ⚠ recordNode 插入失败! traceId={}, nodeId={}, error={}",
                    traceId, nodeId, e.getMessage(), e);
        }
    }

    @Override
    public void recordNodeError(String traceId, String nodeId, String message) {
        try {
            log.info("[TRACE_DB] >>> recordNodeError: traceId={}, nodeId={}, error='{}'",
                    traceId, nodeId, message);

            NodeRecord record = new NodeRecord();
            record.setNodeId(nodeId);
            record.setTraceId(traceId);
            record.setStatus("ERROR");
            record.setErrorMessage(message);

            long t1 = System.currentTimeMillis();
            int result = nodeRecordMapper.updateById(record);
            log.info("[TRACE_DB] <<< recordNodeError 更新完成, result={}, traceId={}, 耗时={}ms",
                    result, traceId, System.currentTimeMillis() - t1);

        } catch (Exception e) {
            log.error("[TRACE_DB] ⚠ recordNodeError 更新失败! traceId={}, nodeId={}, error={}",
                    traceId, nodeId, e.getMessage(), e);
        }
    }

    @Override
    public void recordError(String traceId, String message) {
        try {
            log.info("[TRACE_DB] >>> recordError: traceId={}, error='{}'", traceId, message);

            TraceRecord record = new TraceRecord();
            record.setTraceId(traceId);
            record.setStatus("ERROR");
            record.setErrorMessage(message);

            long t1 = System.currentTimeMillis();
            int result = traceRecordMapper.updateById(record);
            log.info("[TRACE_DB] <<< recordError 更新完成, result={}, traceId={}, 耗时={}ms",
                    result, traceId, System.currentTimeMillis() - t1);

        } catch (Exception e) {
            log.error("[TRACE_DB] ⚠ recordError 更新失败! traceId={}, error={}",
                    traceId, e.getMessage(), e);
        }
    }
}