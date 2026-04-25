package com.XYai.myai.rag.etlpipeline;

import com.XYai.myai.rag.milvus.MilvusCollectionService;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

// ...existing code...

@Component
public class UploadTaskStore {

    private static final String TASK_KEY_PREFIX = "UploadTask:";
    private static final String REDIS_TASK_MAP = "xyai:upload:tasks";
    @Resource
    private RedissonClient redissonClient;
    @Autowired(required = false)
    private MilvusCollectionService milvusCollectionService;

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

    public TaskState copy(String taskId, String sourceCollection, String targetCollection) {
        // 如果注入了 MilvusCollectionService，则在上传层进行短轮询等待，避免直接发起冲突操作
        boolean ready = true;

        if (!ready) {
            // 目标集合仍被占用，记录等待态并返回
            TaskState waiting = TaskState.builder()
                    .taskId(taskId)
                    .status("WAITING")
                    .currentNodeType("copy")
                    .nodeLabel("跨集合复用")
                    .displayText("目标集合忙，已加入等待队列：" + targetCollection)
                    .message("等待目标集合解锁")
                    .progress(50)
                    .eventType("snapshot")
                    .startTime(existingStartTime(taskId))
                    .build();
            return put(taskId, waiting);
        }

        TaskState state = TaskState.builder()
                .taskId(taskId)
                .status("RUNNING")
                .currentNodeType("copy")
                .nodeLabel("跨集合复用")
                .displayText("检测到相同内容已存在于集合 [" + sourceCollection + "]，正在复制到当前集合 [" + targetCollection + "]")
                .message("跨集合复制")
                .progress(70)
                .eventType("snapshot")
                .startTime(existingStartTime(taskId))
                .build();
        return put(taskId, state);
    }

    public TaskState success(String taskId, String message) {
        String displayText = (message == null || message.isBlank()) ? "任务已完成" : message;
        TaskState state = TaskState.builder()
                .taskId(taskId)
                .status("SUCCESS")
                .displayText(displayText)
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
        // Primary store is Redisson map
        try {
            RMap<String, String> map = redissonClient.getMap(REDIS_TASK_MAP);
            String json = map.get(taskId);
            if (json != null && !json.isBlank()) {
                try {
                    return JSON.parseObject(json, TaskState.class);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public void remove(String taskId) {
        try {
            RMap<String, String> map = redissonClient.getMap(REDIS_TASK_MAP);
            map.remove(taskId);
        } catch (Exception ignored) {
        }
    }

    private TaskState put(String taskId, TaskState state) {
        try {
            RMap<String, String> map = redissonClient.getMap(REDIS_TASK_MAP);
            map.put(taskId, JSON.toJSONString(state));
        } catch (Exception ignored) {
        }
        return state;
    }

    private Long existingStartTime(String taskId) {
        try {
            RMap<String, String> map = redissonClient.getMap(REDIS_TASK_MAP);
            String json = map.get(taskId);
            if (json != null) {
                try {
                    TaskState ts = JSON.parseObject(json, TaskState.class);
                    if (ts != null && ts.getStartTime() != null) return ts.getStartTime();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return System.currentTimeMillis();
    }

    // redisKey method no longer used; kept for historical reference

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
            case "copy" -> "跨集合复用";
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
            case "copy" -> 70;
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
