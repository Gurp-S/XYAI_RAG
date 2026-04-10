package com.XYai.myai.RAG.Aop.Annotation;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TaskTracker {
    private final Map<String, Map<String, Object>> tasks = new ConcurrentHashMap<>();

    public void update(String fileName, String status, int progress) {
        Map<String, Object> data = new HashMap<>();
        data.put("status", status);
        data.put("progress", progress);
        data.put("updateTime", System.currentTimeMillis());
        tasks.put(fileName, data);
    }

    public Map<String, Object> get(String fileName) {
        return tasks.get(fileName);
    }
}