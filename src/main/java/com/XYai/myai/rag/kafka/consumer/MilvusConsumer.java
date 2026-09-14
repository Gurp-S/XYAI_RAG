package com.XYai.myai.rag.kafka.consumer;

import com.XYai.myai.rag.kafka.IdempotentChecker;
import com.XYai.myai.rag.kafka.event.MilvusEvent;
import com.XYai.myai.rag.milvus.MilvusFileManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MilvusConsumer {

    @Resource
    private MilvusFileManager milvusFileManager;
    @Resource
    private IdempotentChecker idempotentChecker;

    @KafkaListener(topics = "milvus-cmd", groupId = "xyai-neo4j",
            concurrency = "2", containerFactory = "lightRetryListenerFactory")
    public void consumer(MilvusEvent event, Acknowledgment ac){
        if(idempotentChecker.isProcessed((event.getEventId()))){
            ac.acknowledge();
            return;
        }
        try {
            switch (event.getCommandType()){
                case "DELETE_CHUNK" ->{
                    milvusFileManager.deleteFileChunk(event.getFileChunkId());
                }
            }
            idempotentChecker.markProcessed(event.getEventId());
            ac.acknowledge();
        } catch (Exception e) {
            log.error("Milvus操作失败", e);
            throw new RuntimeException(e);
        }
    }

}
