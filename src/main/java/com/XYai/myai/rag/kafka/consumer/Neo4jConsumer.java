package com.XYai.myai.rag.kafka.consumer;

import com.XYai.myai.rag.graph.Neo4jKnowledgeGraphService;
import com.XYai.myai.rag.kafka.IdempotentChecker;
import com.XYai.myai.rag.kafka.event.Neo4jEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.HashSet;

@Component
@Slf4j
public class Neo4jConsumer {

    @Resource
    private Neo4jKnowledgeGraphService neo4jService;
    @Resource
    private IdempotentChecker idempotentChecker;

    @KafkaListener(topics = "neo4j-cmd", groupId = "xyai-neo4j",
            concurrency = "2", containerFactory = "lightRetryListenerFactory")
    public void consume(Neo4jEvent event, Acknowledgment ack) {
        if (idempotentChecker.isProcessed(event.getEventId())) {
            ack.acknowledge();
            return;
        }
        try {
            switch (event.getCommandType()) {
                case "INSERT_TRIPLES" -> {
                    neo4jService.batchInsertTriples(event.getTriples());
                }
                case "DELETE_RELATIONS" ->
                        neo4jService.deleteChunkRelations(new HashSet<>(event.getChunkIds()));
                case "TRIGGER_MAINTENANCE" ->
                        neo4jService.triggerCommunityMaintenanceIfNeeded();
            }
            idempotentChecker.markProcessed(event.getEventId());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("Neo4j操作失败", e);
            throw new RuntimeException(e);
        }
    }
}