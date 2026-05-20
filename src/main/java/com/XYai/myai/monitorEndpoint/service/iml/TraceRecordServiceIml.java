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

    // 定义日期时间格式
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    @Resource
    private TraceRecordMapper traceRecordMapper;
    @Resource
    private NodeRecordMapper nodeRecordMapper;

    @Override
    public void startRun(String traceId, String name) {
        TraceRecord record = TraceRecord.builder()
                .traceId(traceId)
                .name(name)
                .startTime(LocalDateTime.now())
                .status("RUNNING")
                .build();
        traceRecordMapper.insert(record);
    }

    @Override
    public void recordNode(String traceId, String nodeId, Object name, Object type) {
        NodeRecord record = NodeRecord.builder()
                .nodeId(nodeId)
                .traceId(traceId)
                .nodeName(name != null ? name.toString() : null)
                .nodeType(type != null ? type.toString() : null)
                .startTime(LocalDateTime.now())
                .status("RUNNING")
                .build();
        nodeRecordMapper.insert(record);
    }


    @Override
    public void updateNode(String traceId, String nodeId, Object name, Object type, long costTime) {
        nodeRecordMapper.updateNodeStatus(nodeId, "SUCCESS", LocalDateTime.now(), costTime, null);
    }

    @Override
    public void recordNodeWarn(String traceId, String nodeId, String warnMessage, long costTime) {
        nodeRecordMapper.updateNodeStatus(nodeId, "WARN", LocalDateTime.now(), costTime, warnMessage);
    }

    @Override
    public void recordRunWarn(String traceId, String warnMessage, long costTime) {
        traceRecordMapper.updateByTraceId(traceId, "WARN", LocalDateTime.now(), costTime, warnMessage);
    }

    @Override
    public void recordNodeError(String traceId, String nodeId, String message) {
        nodeRecordMapper.updateNodeStatus(nodeId, "ERROR", LocalDateTime.now(), null, message);
    }

    @Override
    public void finishRun(String traceId, long coseTime) {
        traceRecordMapper.updateByTraceId(traceId, "SUCCESS", LocalDateTime.now(), coseTime, null);
    }

    @Override
    public void recordError(String traceId, String message) {
        traceRecordMapper.updateByTraceId(traceId, "ERROR", LocalDateTime.now(), null, message);
    }
}