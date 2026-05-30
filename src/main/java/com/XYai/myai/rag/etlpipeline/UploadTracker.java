package com.XYai.myai.rag.etlpipeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 跟踪一个文件的所有chunk是否全部完成。
 *
 * 原理：
 *   Redis key           ─ 说明
 *   upload:{taskId}:total  ─ 总chunk数(etl-file消费者写入)
 *   upload:{taskId}:done   ─ 已完成chunk数(每个chunk完成后INCR)
 *   upload:{taskId}:triples ─ 收集到的三元组，最后一个chunk负责批量入库
 *
 *   每个chunk完成后 INCR done，当 done == total 时代表全完成。
 *   使用 INCR 是原子的，多个并发消费者同时调用不会冲突。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UploadTracker {

    private static final String PREFIX = "xyai:upload:";
    private static final long TTL_SECONDS = 3600;  // 1小时后自动过期，防残留

    private final StringRedisTemplate redis;

    /** etl-file消费者：初始化计数器 */
    public void initFile(String taskId, int totalChunks) {
        String totalKey = PREFIX + taskId + ":total";
        String doneKey = PREFIX + taskId + ":done";
        redis.opsForValue().set(totalKey, String.valueOf(totalChunks), TTL_SECONDS, TimeUnit.SECONDS);
        redis.opsForValue().set(doneKey, "0", TTL_SECONDS, TimeUnit.SECONDS);
        log.info("文件跟踪初始化: taskId={}, 总chunk数={}", taskId, totalChunks);
    }

    /** etl-chunk消费者：每完成一个chunk调用一次 */
    public void completeOne(String taskId) {
        String doneKey = PREFIX + taskId + ":done";
        Long done = redis.opsForValue().increment(doneKey);
        String total = redis.opsForValue().get(PREFIX + taskId + ":total");
        if (done != null && total != null) {
            log.debug("chunk完成: taskId={}, {}/{}", taskId, done, total);
        }
    }

    /** 判断文件是否全部完成 */
    public boolean isFileComplete(String taskId) {
        String done = redis.opsForValue().get(PREFIX + taskId + ":done");
        String total = redis.opsForValue().get(PREFIX + taskId + ":total");
        if (done == null || total == null) return false;
        return Long.parseLong(done) >= Long.parseLong(total);
    }

    /** 收尾后清理 */
    public void cleanup(String taskId) {
        redis.delete(PREFIX + taskId + ":total");
        redis.delete(PREFIX + taskId + ":done");
        redis.delete(PREFIX + taskId + ":triples");
        log.info("文件跟踪清理: taskId={}", taskId);
    }

    /** 获取当前完成数（对账用） */
    public int getDoneCount(String taskId) {
        String val = redis.opsForValue().get(PREFIX + taskId + ":done");
        return val == null ? 0 : Integer.parseInt(val);
    }
}