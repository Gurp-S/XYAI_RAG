package com.XYai.myai.rag.kafka.consumer;

import com.XYai.myai.rag.kafka.IdempotentChecker;
import com.XYai.myai.rag.kafka.event.AclCommandEvent;
import com.XYai.myai.rag.milvus.MilvusAclManager;
import com.XYai.myai.user.LoginUserInfoManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AclConsumer {

    @Resource
    private IdempotentChecker idempotentChecker;
    @Resource
    private MilvusAclManager milvusAclManager;

    @KafkaListener(topics = "acl-cmd", groupId = "xyai-acl",
            concurrency = "4", containerFactory = "defaultFactory")
    public void consume(AclCommandEvent event, Acknowledgment ack) {
        if (idempotentChecker.isProcessed(event.getEventId())) {
            ack.acknowledge();
            return;
        }
        try {
            switch (event.getCommandType()) {
                case "ADD_FILE" -> milvusAclManager.addFileChunkAcl(event.getFileId(),event.getChunkId(),
                        event.getCollectionName());

                case "ADD_CHUNKS" -> {
                    // 消费者线程没有ThreadLocal用户上下文，需要手动设置
                    if (event.getUserId() != null) {
                        LoginUserInfoManager.setUserId(event.getUserId());
                    }
                    try {
                        milvusAclManager.addFileUserACl(event.getFileId(),
                                event.getCollectionName(), event.getChunkIds());
                        // 确保用户集合权限被标记，否则 FilterPostProcessor 会过滤掉所有结果
                        if (event.getUserId() != null) {
                            milvusAclManager.ensureUserCollectionAcl(event.getUserId(),
                                    event.getCollectionName());
                        }
                    } finally {
                        LoginUserInfoManager.remove();
                    }
                }
            }
            idempotentChecker.markProcessed(event.getEventId());
            ack.acknowledge();
        } catch (Exception e) {
            log.error("ACL处理失败", e);
            throw new RuntimeException(e);  // Kafka 重试
        }
    }
}
