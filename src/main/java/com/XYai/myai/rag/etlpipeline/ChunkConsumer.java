package com.XYai.myai.rag.etlpipeline;

import com.XYai.myai.mapper.FileRecordMapper;
import com.XYai.myai.rag.etlpipeline.consumers.Enricher;
import com.XYai.myai.rag.etlpipeline.consumers.Indexer;
import com.XYai.myai.rag.kafka.IdempotentChecker;
import com.XYai.myai.rag.kafka.event.AclCommandEvent;
import com.XYai.myai.rag.kafka.event.ChunkEvent;
import com.XYai.myai.rag.milvus.pojo.FileRecord;
import com.XYai.myai.user.LoginUserInfoManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class ChunkConsumer {
    @Resource
    private Enricher enricher;
    @Resource
    private Indexer indexer;
    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Resource
    private FileRecordMapper fileRecordMapper;
    @Resource
    private IdempotentChecker idempotentChecker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    @KafkaListener(
            topics = "etl-chunk",
            groupId = "xyai-etl-chunk",
            concurrency = "8",              // 8个消费者线程并行
            containerFactory = "chunkFactory"  // 重试5次，间隔2秒
    )
    public void consume(ChunkEvent chunkEvent, Acknowledgment acknowledgment){

        log.info("===== ChunkConsumer 收到消息: eventId={}, taskId={}, chunkId={}",
                chunkEvent.getEventId(), chunkEvent.getTaskId(), chunkEvent.getChunkId());

        if(idempotentChecker.isProcessed(chunkEvent.getEventId())){
            log.info("消息已处理,跳过: eventId={}", chunkEvent.getEventId());
            acknowledgment.acknowledge();
            return;
        }

        String fileChunkId = chunkEvent.getChunkId();
        int chunkId = Integer.parseInt(fileChunkId.split(":")[1]);
        String fileId = fileChunkId.split(":")[0];
        String chunkText = chunkEvent.getChunkText();
        String taskId = chunkEvent.getTaskId();
        int chunkSize = chunkEvent.getChunkSize();

        // 加入完成的id（按文件隔离：同一次上传的多个文件共用 taskId，
        // 若共用同一个 done/processed 集合，不同文件的 chunkId 会互相污染，
        // 导致 finishFile 提前/延迟触发、ACL 位图写错文件）
        String doneKey = "upload:" + taskId + ":done:" + fileId;
        String processedKey = "upload:" + taskId + ":processed:" + fileId;
        Long increment = stringRedisTemplate.opsForValue().increment(processedKey);
        try {
            Map<String, Object> chunkMeta = chunkEvent.getChunkMetadata() == null
                    ? new java.util.HashMap<>() : new java.util.HashMap<>(chunkEvent.getChunkMetadata());
            // 修正文档名：Parser 阶段 MultipartFile.getName() 返回的是表单字段名（如 "file"），
            // 以 Kafka 事件携带的真实原始文件名为准
            if (chunkEvent.getFileName() != null && !chunkEvent.getFileName().isBlank()) {
                chunkMeta.put("fileName", chunkEvent.getFileName());
            }
            Document doc = Document.builder()
                    .id(fileChunkId)
                    .text(chunkText)
                    .metadata(chunkMeta)
                    .build();

            // 进行增强
            Document enriched = enricher.execute(doc);

            // 消费者线程没有ThreadLocal用户上下文，需要手动设置
            if (chunkEvent.getUserId() != null) {
                LoginUserInfoManager.setUserId(chunkEvent.getUserId());
            }

            // 进行入库
            indexer.execute(enriched, chunkEvent.getCollectionName());

            // redis + 1
            stringRedisTemplate.opsForSet().add(doneKey, String.valueOf(chunkId));

            // 是否全部完成（>= 兼容失败重试导致的重复计数）
            if(increment!=null && increment >= chunkSize){
                // 并发消费下，收到最后一条消息不代表其余在途消息已入库完成；
                // 必须等 done 集合凑齐再 finishFile，否则 ACL 授权只含部分块 + Redis 状态被提前清理
                Set<String> doneChunkIds = waitForAllChunks(doneKey, chunkSize, 30);
                finishFile(chunkEvent, chunkEvent.getFileName(), doneChunkIds);
            }

            log.info("chunk处理进度: taskId={}, chunkId={}", taskId, chunkId);
            idempotentChecker.markProcessed(chunkEvent.getEventId());
            acknowledgment.acknowledge();
        } catch (Exception e) {
            log.error("chunk处理失败: taskId={}, chunkId={}",
                    taskId, chunkId, e);
            throw new RuntimeException(e);
        } finally {
            LoginUserInfoManager.remove();
        }
    }

    /**
     * 轮询等待 done 集合凑齐（所有分块成功入库）。超时则返回当前集合，
     * 由 finishFile 记录失败/缺失分块日志，避免无限阻塞消费线程。
     */
    private Set<String> waitForAllChunks(String doneKey, int chunkSize, int maxWaitSeconds) {
        long deadline = System.currentTimeMillis() + maxWaitSeconds * 1000L;
        Set<String> done = java.util.Collections.emptySet();
        while (System.currentTimeMillis() < deadline) {
            done = stringRedisTemplate.opsForSet().members(doneKey);
            if (done != null && done.size() >= chunkSize) {
                return done;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.warn("等待分块完成超时: done={}, expected={}", done == null ? 0 : done.size(), chunkSize);
        return done;
    }

    private void finishFile(ChunkEvent chunkEvent, String fileName, Set<String> doneChunkIds) {
        String lockKey = "upload:" + chunkEvent.getTaskId() + ":finishLock:" + chunkEvent.getChunkId().split(":")[0];
        RLock lock = redissonClient.getLock(lockKey);
        try {
            // 加锁等待 1 秒过期时间 30 秒
            if (lock.tryLock(1, 30, TimeUnit.SECONDS)) {
                // 双重检查（与 done/processed 同键规则：按文件隔离）
                String processedKey = "upload:" + chunkEvent.getTaskId() + ":processed:"
                        + chunkEvent.getChunkId().split(":")[0];
                String processed = stringRedisTemplate.opsForValue().get(processedKey);
                long finalCount = processed == null ? 0 : Long.parseLong(processed);
                if (finalCount < chunkEvent.getChunkSize()) {
                    log.info("文件已完成处理 重复处理 finishFile: taskId={}", chunkEvent.getTaskId());
                    return;
                }

                // 失败分块判断
                int successChunks = doneChunkIds.size();
                List<Integer> failOrSkipChunk = new ArrayList<>();
                if (successChunks < chunkEvent.getChunkSize()) {
                    if (successChunks == 0) {
                        log.error("文件上传失败，无任何成功分块: taskId={}", chunkEvent.getTaskId());
                        return;
                    }
                    for (int i = 1; i <= chunkEvent.getChunkSize(); i++) {
                        if (!doneChunkIds.contains(String.valueOf(i))) {
                            failOrSkipChunk.add(i);
                        }
                    }
                    log.info("文件部分完成: taskId={}, 成功分块数={}, 失败/跳过分块:{}",
                            chunkEvent.getTaskId(), successChunks, failOrSkipChunk);
                } else {
                    log.info("文件全部完成: taskId={}", chunkEvent.getTaskId());
                }

                //上传
                List<Integer> chunkIdList = doneChunkIds.stream()
                        .filter(s -> s.matches("\\d+"))
                        .map(Integer::parseInt)
                        .toList();
                // 发送 ACL 命令（同步等待 ack：授权消息丢失 = 文件级授权空洞）
                try {
                    kafkaTemplate.send("acl-cmd", null, new AclCommandEvent(
                            UUID.randomUUID().toString(), "ADD_CHUNKS",
                            chunkEvent.getCollectionName(), chunkEvent.getChunkId().split(":")[0],
                            null, chunkIdList, chunkEvent.getUserId(), null,
                            chunkIdList.size())
                    ).get(15, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.error("ACL 命令发送失败: taskId={}, file={}", chunkEvent.getTaskId(), chunkEvent.getChunkId(), e);
                    throw new RuntimeException("ACL 命令发送失败", e);
                }

                // 写 MySQL
                FileRecord record = new FileRecord();
                record.setFileChunkId(chunkEvent.getChunkId().split(":")[0]);
                record.setFileName(fileName);
                record.setFileUsingCount(0L);
                try {
                    fileRecordMapper.insert(record);
                } catch (org.springframework.dao.DuplicateKeyException e) {
                    log.warn("文件记录失败 id: {}", record.getFileChunkId());
                }
                // 清理 Redis（按文件隔离的键）
                String fId = chunkEvent.getChunkId().split(":")[0];
                stringRedisTemplate.delete("upload:" + chunkEvent.getTaskId() + ":done:" + fId);
                stringRedisTemplate.delete("upload:" + chunkEvent.getTaskId() + ":processed:" + fId);
            } else {
                log.warn("获取 finishLock 失败: taskId={}", chunkEvent.getTaskId());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取 finishLock 中断: taskId={}", chunkEvent.getTaskId());
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
