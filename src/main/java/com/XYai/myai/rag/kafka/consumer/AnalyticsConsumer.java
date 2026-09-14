package com.XYai.myai.rag.kafka.consumer;

import com.XYai.myai.rag.channel.MultiChannelRetrievalEngine;
import com.XYai.myai.rag.chat.ModelInvocationService;
import com.XYai.myai.rag.evaluate.pojo.SystemEvaluate;
import com.XYai.myai.rag.evaluate.service.SystemEvaluateService;
import com.XYai.myai.rag.kafka.IdempotentChecker;
import com.XYai.myai.rag.kafka.event.AnalyticsEvent;
import com.XYai.myai.rag.milvus.pojo.FileRecord;
import com.XYai.myai.xyAdmin.pojo.TokenUse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AnalyticsConsumer {

    @Resource
    private IdempotentChecker idempotentChecker;
    @Resource
    private MultiChannelRetrievalEngine multiChannelRetrievalEngine;
    @Resource
    private ModelInvocationService modelInvocationService;
    @Resource
    private SystemEvaluateService systemEvaluateService;

    @KafkaListener(topics = "analytics-event", groupId = "xyai-analytics",
            containerFactory = "defaultFactory")
    public void consume(AnalyticsEvent event, Acknowledgment ack) {
        if (idempotentChecker.isProcessed(event.getEventId())) {
            ack.acknowledge();
            return;
        }
        SystemEvaluate systemEvaluate = event.getSystemEvaluate();
        TokenUse tokenUse = event.getTokenUse();
        FileRecord fileRecord = event.getFileRecord();
        switch (event.getEventType()) {
            case "evaluate" -> systemEvaluateService.submitEvaluate(systemEvaluate.getConversationId(),systemEvaluate.getMessage()
                    ,systemEvaluate.getRetrievedChunks(),systemEvaluate.getLatencyMs(),systemEvaluate.getUserQuestion());
            case "use_count" -> multiChannelRetrievalEngine.incrementFileChunkCount(fileRecord.getFileChunkId());
            case "save_token" -> modelInvocationService.saveTokenUseAsync(tokenUse.getConversationId(), tokenUse.getChatMessageId(),
                    tokenUse.getPromptTokens(), tokenUse.getCompletionTokens(), tokenUse.getUserId(), tokenUse.getCostMs(),
                    tokenUse.getModelName(), tokenUse.getCallType());
        }
        ack.acknowledge();
    }
}