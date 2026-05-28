package com.XYai.myai.monitorEndpoint;

import jakarta.annotation.Resource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/thread-pool")
public class ThreadPoolMonitorEndpoint {

    @Resource(name = "userExecutor")
    private TaskExecutor userExecutor;

    @Resource(name = "memeryExecutor")
    private TaskExecutor memeryExecutor;

    @Resource(name = "uploadExecutor")
    private TaskExecutor uploadExecutor;

    @Resource(name = "searchChannelExecutor")
    private TaskExecutor searchChannelExecutor;

    @GetMapping
    public Map<String, Object> monitor() {
        Map<String, Object> result = new HashMap<>();
        result.put("userExecutor", getExecutorInfo(userExecutor));
        result.put("memeryExecutor", getExecutorInfo(memeryExecutor));
        result.put("uploadExecutor", getExecutorInfo(uploadExecutor));
        result.put("searchChannelExecutor", getExecutorInfo(searchChannelExecutor));
        return result;
    }

    private Map<String, Object> getExecutorInfo(TaskExecutor executor) {
        Map<String, Object> info = new HashMap<>();
        info.put("type", "virtual-thread");
        info.put("executor", executor.getClass().getSimpleName());
        return info;
    }
}