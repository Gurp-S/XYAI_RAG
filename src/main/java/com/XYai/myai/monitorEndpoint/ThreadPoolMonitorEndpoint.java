package com.XYai.myai.monitorEndpoint;

import jakarta.annotation.Resource;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

@RestController
@RequestMapping("/thread-pool")
public class ThreadPoolMonitorEndpoint {

    @Resource(name = "userExecutor")
    private ThreadPoolTaskExecutor userExecutor;

    @Resource(name = "memeryExecutor")
    private ThreadPoolTaskExecutor memeryExecutor;

    @Resource(name = "uploadExecutor")
    private ThreadPoolTaskExecutor uploadExecutor;

    @Resource(name = "searchChannelExecutor")
    private ThreadPoolTaskExecutor searchChannelExecutor;

    @GetMapping
    public Map<String, Object> monitor() {
        Map<String, Object> result = new HashMap<>();
        result.put("userExecutor", getExecutorInfo(userExecutor));
        result.put("memeryExecutor", getExecutorInfo(memeryExecutor));
        result.put("uploadExecutor", getExecutorInfo(uploadExecutor));
        result.put("searchChannelExecutor", getExecutorInfo(searchChannelExecutor));
        return result;
    }

    private Map<String, Object> getExecutorInfo(ThreadPoolTaskExecutor executor) {
        Map<String, Object> info = new HashMap<>();
        ThreadPoolExecutor pool = executor.getThreadPoolExecutor();

        info.put("corePoolSize", executor.getCorePoolSize());
        info.put("maxPoolSize", executor.getMaxPoolSize());
        info.put("activeCount", pool.getActiveCount());         // 活跃线程
        info.put("poolSize", pool.getPoolSize());               // 当前总线程
        info.put("queueSize", pool.getQueue().size());          // 队列积压
        info.put("queueRemainingCapacity", pool.getQueue().remainingCapacity());
        info.put("completedTaskCount", pool.getCompletedTaskCount());
        info.put("taskCount", pool.getTaskCount());
        info.put("threadNamePrefix", executor.getThreadNamePrefix());

        return info;
    }
}