package com.XYai.myai.rag.kafka.consumer;

import com.XYai.myai.rag.chat.pojo.ChatMessage;
import com.XYai.myai.rag.kafka.IdempotentChecker;
import com.XYai.myai.rag.kafka.event.MemoryEvent;
import com.XYai.myai.rag.memory.ConversationMemorySummaryService;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class MemoryConsumer {

    @Resource
    private ConversationMemorySummaryService memoryService;
    @Resource
    private IdempotentChecker idempotentChecker;



    @KafkaListener(topics = "memory-cmd", groupId = "xyai-memory",
            concurrency = "2", containerFactory = "lightRetryListenerFactory")
    public void consume(MemoryEvent event, Acknowledgment ack) {
        if(idempotentChecker.isProcessed((event.getEventId()))){
            ack.acknowledge();
            return;
        }

        switch (event.getCommandType()) {
            case "SAVE_MESSAGE" -> {
                ChatMessage msg = JSON.parseObject(event.getMessageJson(), ChatMessage.class);
                memoryService.asyncSaveToDatabase(event.getConversationId(), msg);
            }
            case "COMPRESS" -> {
                ChatMessage msg = JSON.parseObject(event.getMessageJson(), ChatMessage.class);
                memoryService.doCompressIfNeeded(event.getConversationId(), msg, event.getUserId());
            }
            case "GENERATE_SUMMARY" -> memoryService.generateAndSaveSummary(event); // 生成保存
        }
        ack.acknowledge();
    }
}