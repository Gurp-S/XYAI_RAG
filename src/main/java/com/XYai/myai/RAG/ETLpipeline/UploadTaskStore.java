package com.XYai.myai.RAG.ETLpipeline;

import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

@Component
public class UploadTaskStore {

    private static final String TASK_KEY_PREFIX = "UploadTask:";

    private final ConcurrentHashMap<String, TaskState> memory = new ConcurrentHashMap<>();

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public TaskState start(String taskId) {
        TaskState state = TaskState.builder()
                .taskId(taskId)
                .status("WAITING")
                .displayText("任务已提交，等待执行")
                .message("任务已提交")
                .progress(5)
                .eventType("snapshot")
                .startTime(System.currentTimeMillis())
                .build();
        return put(taskId, state);
    }

    public TaskState node(String taskId, String nodeType) {
        String label = labelFor(nodeType);
        int progress = progressFor(nodeType);
        TaskState state = TaskState.builder()
                .taskId(taskId)
                .status("RUNNING")
                .currentNodeType(nodeType)
                .nodeLabel(label)
                .displayText(label == null ? "任务处理中" : "正在执行：" + label)
                .message(nodeType)
                .progress(progress)
                .eventType("snapshot")
                .startTime(existingStartTime(taskId))
                .build();
        return put(taskId, state);
    }

    public TaskState success(String taskId, String message) {
        TaskState state = TaskState.builder()
                .taskId(taskId)
                .status("SUCCESS")
                .displayText("任务已完成")
                .message(message)
                .progress(100)
                .eventType("complete")
                .startTime(existingStartTime(taskId))
                .endTime(System.currentTimeMillis())
                .build();
        return put(taskId, state);
    }

    public TaskState error(String taskId, String message) {
        TaskState state = TaskState.builder()
                .taskId(taskId)
                .status("ERROR")
                .displayText("任务失败")
                .message(message)
                .errorMessage(message)
                .progress(100)
                .eventType("complete")
                .startTime(existingStartTime(taskId))
                .endTime(System.currentTimeMillis())
                .build();
        return put(taskId, state);
    }

    public TaskState get(String taskId) {
        TaskState state = memory.get(taskId);
        if (state != null) {
            return state;
        }
        if (stringRedisTemplate != null) {
            String json = stringRedisTemplate.opsForValue().get(redisKey(taskId));
            if (json != null && !json.isBlank()) {
                try {
                    return JSON.parseObject(json, TaskState.class);
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    public void remove(String taskId) {
        memory.remove(taskId);
    }

    private TaskState put(String taskId, TaskState state) {
        memory.put(taskId, state);
        if (stringRedisTemplate != null) {
            try {
                stringRedisTemplate.opsForValue().set(redisKey(taskId), JSON.toJSONString(state));
            } catch (Exception ignored) {
                // Redis is just a mirror.
            }
        }
        return state;
    }

    private Long existingStartTime(String taskId) {
        TaskState current = memory.get(taskId);
        return current == null ? System.currentTimeMillis() : current.getStartTime();
    }

    private String redisKey(String taskId) {
        return TASK_KEY_PREFIX + taskId;
    }

    private String labelFor(String nodeType) {
        if (nodeType == null) {
            return null;
        }
        return switch (nodeType.trim().toLowerCase()) {
            case "fetcher" -> "获取源文件";
            case "parser" -> "解析文档";
            case "enricher" -> "语义增强";
            case "chunker" -> "内容分块";
            case "indexer" -> "向量入库";
            default -> nodeType;
        };
    }

    private int progressFor(String nodeType) {
        if (nodeType == null) {
            return 10;
        }
        return switch (nodeType.trim().toLowerCase()) {
            case "fetcher" -> 20;
            case "parser" -> 40;
            case "enricher" -> 60;
            case "chunker" -> 80;
            case "indexer" -> 95;
            default -> 10;
        };
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskState {
        private String taskId;
        private String status;
        private String currentNodeType;
        private String nodeLabel;
        private String message;
        private String displayText;
        private String eventType;
        private Integer progress;
        private Long startTime;
        private Long endTime;
        private String errorMessage;
    }
}
