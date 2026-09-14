package com.XYai.myai.rag.kafka.consumer;

import com.XYai.myai.monitorEndpoint.service.TraceRecordService;
import com.XYai.myai.rag.kafka.event.TraceLogEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Slf4j
public class TraceConsumer {

    @Resource
    private TraceRecordService traceRecordService;

    @KafkaListener(topics = "trace-log", groupId = "xyai-trace",
            containerFactory = "batchListenerFactory")  // 攒批
    public void consume(List<TraceLogEvent> events, Acknowledgment ack) {
        if (events == null || events.isEmpty()) {
            ack.acknowledge();
            return;
        }
        log.debug("Trace消费 batchSize={}, types={}", events.size(),
                events.stream().map(TraceLogEvent::getEventType).toList());
        try {
            for (TraceLogEvent event : events) {
                switch (event.getEventType()) {
                    case "start" -> traceRecordService.startRun(event.getTraceId(), event.getNodeName());
                    case "finish" -> traceRecordService.updateRun(event.getTraceId(), "SUCCESS", null, event.getCostNanos());
                    case "error" -> traceRecordService.updateRun(event.getTraceId(), "ERROR", event.getMessage(), null);
                    case "warn" -> traceRecordService.updateRun(event.getTraceId(), "WARN", event.getMessage(), event.getCostNanos());
                    case "node" -> traceRecordService.recordNode(event.getTraceId(), event.getNodeId(), event.getNodeName(), event.getMessage());
                    case "node_success" -> traceRecordService.updateNode(event.getTraceId(), "SUCCESS", event.getNodeId(), event.getNodeName(), null, event.getCostNanos(), null);
                    case "node_warn" -> traceRecordService.updateNode(event.getTraceId(), "WARN", event.getNodeId(), event.getNodeName(), null, event.getCostNanos(), event.getMessage());
                    case "node_error" -> traceRecordService.updateNode(event.getTraceId(), "ERROR", event.getNodeId(), event.getNodeName(), null, event.getCostNanos(), event.getMessage());
                }
            }
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Trace消费失败", e);
            throw new RuntimeException(e);
        }
    }
}