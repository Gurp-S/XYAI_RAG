package com.XYai.myai.rag.etlpipeline;

import com.XYai.myai.mapper.FileRecordMapper;
import com.XYai.myai.rag.etlpipeline.consumers.Chunker;
import com.XYai.myai.rag.etlpipeline.consumers.Parser;
import com.XYai.myai.rag.etlpipeline.consumers.TextCleaner;
import com.XYai.myai.rag.etlpipeline.pojo.FileBackedMultipartFile;
import com.XYai.myai.rag.etlpipeline.pojo.InMemoryMultipartFile;
import com.XYai.myai.rag.kafka.IdempotentChecker;
import com.XYai.myai.rag.kafka.event.ChunkEvent;
import com.XYai.myai.rag.kafka.event.FileUploadEvent;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class FileUploadConsumer {
    @Resource
    private Parser parser;
    @Resource
    private Chunker chunker;
    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Resource
    private UploadTracker uploadTracker;
    @Resource
    private IdempotentChecker idempotentChecker;
    @Resource
    private TextCleaner textCleaner;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private FileRecordMapper fileRecordMapper;


    @KafkaListener(topics = "etl-file", groupId = "xyai-ingest",
            concurrency = "4", containerFactory = "fileListenerFactory")
    public void consume(FileUploadEvent fileUploadEvent, Acknowledgment acknowledgment) {
        log.info("===== FileUploadConsumer 收到消息: eventId={}, taskId={}, fileHash={}, fileName={}, fileBytesSize={}, tempFilePath={}",
                fileUploadEvent.getEventId(), fileUploadEvent.getTaskId(), fileUploadEvent.getFileHash(),
                fileUploadEvent.getOriginalFilename(),
                fileUploadEvent.getFileBytes() != null ? fileUploadEvent.getFileBytes().length : 0,
                fileUploadEvent.getTempFilePath());
        if (idempotentChecker.isProcessed(fileUploadEvent.getEventId())) {
            log.info("消息已处理,跳过: eventId={}", fileUploadEvent.getEventId());
            acknowledgment.acknowledge();
            return;
        }
        String taskId = fileUploadEvent.getTaskId();
        String fileHash = fileUploadEvent.getFileHash();
        List<Integer> upChunks = fileUploadEvent.getUpChunks();
        try {
            MultipartFile file = resolveFile(fileUploadEvent);
            log.info("文件解析完成: fileName={}, fileSize={}", file.getOriginalFilename(), file.getSize());
            String fileName = file.getOriginalFilename();
            if (fileName == null || fileName.isBlank()) {
                fileName = file.getName();
            }
            // ── Parser
            Document text = parser.execute(file, fileHash);
            if (text == null) {
                log.warn("解析结果为空: taskId={}", taskId);
                acknowledgment.acknowledge();
                return;
            }
            // ── cleaned：
            Document cleaned = textCleaner.execute(text);
            if (cleaned == null) {
                log.warn("清洗结果为空: taskId={}", taskId);
                acknowledgment.acknowledge();
                return;
            }

            // ── Chunker：
            List<Document> chunks = chunker.execute(cleaned, upChunks);
            if (chunks == null || chunks.isEmpty()) {
                log.warn("分块结果为空: taskId={}", taskId);
                acknowledgment.acknowledge();
                return;
            }

            // keys
            String doneKey = "upload:" + taskId + ":done";
            String processedKey = "upload:" + taskId + ":processed";
            String finishLock = "upload:" + taskId + ":finishLock";

            // ── 每个 chunk 发一条消息（同步发送 + 重试：fire-and-forget 在 broker 抖动时会静默丢消息）──
            for (Document chunk : chunks) {
                ChunkEvent ce = new ChunkEvent();
                ce.setEventId(UUID.randomUUID().toString());
                ce.setTaskId(taskId);
                ce.setChunkId(chunk.getId());
                ce.setChunkSize(chunks.size());
                ce.setChunkText(chunk.getText());
                ce.setChunkMetadata(chunk.getMetadata());
                ce.setCollectionName(fileUploadEvent.getCollectionName());
                ce.setUserId(fileUploadEvent.getUserId());
                ce.setFileName(fileName);
                sendReliable("etl-chunk", ce);
            }
            // 幂等
            idempotentChecker.markProcessed(fileUploadEvent.getEventId());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("文件处理失败: taskId={}", taskId, e);
            throw new RuntimeException(e);
        }
    }


    /**
     * 可靠发送：等待 broker ack，失败重试 3 次（退避 1s/2s/4s），最终失败抛异常触发消息重投。
     * 丢一条 chunk 消息 = 该块永远不会被索引（finishFile 只按收到数判完成），必须同步确认。
     */
    private void sendReliable(String topic, ChunkEvent ce) {
        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                kafkaTemplate.send(topic, ce.getChunkId(), ce).get(15, java.util.concurrent.TimeUnit.SECONDS);
                return;
            } catch (Exception e) {
                log.warn("Kafka 发送失败 attempt={}/{}, topic={}, chunkId={}: {}",
                        attempt, maxAttempts, topic, ce.getChunkId(), e.getMessage());
                if (attempt == maxAttempts) {
                    throw new RuntimeException("Kafka 发送最终失败: chunkId=" + ce.getChunkId(), e);
                }
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Kafka 发送中断", ie);
                }
            }
        }
    }

    private MultipartFile resolveFile(FileUploadEvent fileUploadEvent) {
        byte[] fileBytes = fileUploadEvent.getFileBytes();
        if (fileBytes != null && fileBytes.length > 0) {
            return new InMemoryMultipartFile(
                    safeName(fileUploadEvent.getFileName()),
                    fileUploadEvent.getOriginalFilename(),
                    fileUploadEvent.getContentType(),
                    fileBytes
            );
        }
        String tempFilePath = fileUploadEvent.getTempFilePath();
        if (tempFilePath != null && !tempFilePath.isBlank()) {
            File backingFile = new File(tempFilePath);
            return new FileBackedMultipartFile(
                    safeName(fileUploadEvent.getFileName()),
                    fileUploadEvent.getOriginalFilename(),
                    fileUploadEvent.getContentType(),
                    backingFile
            );
        }
        throw new IllegalStateException("FileUploadEvent missing file payload for taskId=" + fileUploadEvent.getTaskId());
    }

    private String safeName(String name) {
        return (name == null || name.isBlank()) ? "file" : name;
    }
}
