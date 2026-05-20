package com.XYai.myai.config;

import com.alibaba.ttl.TtlRunnable;
import com.XYai.myai.user.LoginUserInfoManager;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Slf4j
@Data
@Configuration
public class ThreadPoolConfig {

    // ======================== 通用参数（可根据实际负载调整） ========================
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    // IO 密集型：线程数可设为 CPU 核数 * 2（理论值，需结合实际性能测试）
    private static final int IO_CORE = Math.max(CPU_COUNT * 2, 4);
    private static final int IO_MAX = Math.max(CPU_COUNT * 3, 12);
    // 混合型（兼顾 IO 与计算）
    private static final int MIXED_CORE = CPU_COUNT;
    private static final int MIXED_MAX = CPU_COUNT * 2;

    private static final int QUEUE_CAPACITY = 200; // 适当增大缓冲
    private static final int KEEP_ALIVE_SECONDS = 120; // 非核心线程存活时间
    private static final int AWAIT_TERMINATION_SECONDS = 60;

    // ======================== 统一的 TaskDecorator ========================
    @Bean
    public TaskDecorator taskDecorator() {
        return runnable -> {
            // 捕获父线程上下文
            SecurityContext securityContext = SecurityContextHolder.getContext();
            Long userId = LoginUserInfoManager.getUserId();

            // 先手动传递非 TTL 的上下文，再用 TtlRunnable 包装
            Runnable wrapped = () -> {
                SecurityContext originalSecurity = SecurityContextHolder.getContext();
                Long originalUserId = LoginUserInfoManager.getUserId();
                try {
                    if (securityContext != null && securityContext.getAuthentication() != null) {
                        SecurityContextHolder.setContext(securityContext);
                    }
                    if (userId != null) {
                        LoginUserInfoManager.setUserId(userId);
                    }
                    runnable.run();
                } finally {
                    if (originalSecurity != null && originalSecurity.getAuthentication() != null) {
                        SecurityContextHolder.setContext(originalSecurity);
                    } else {
                        SecurityContextHolder.clearContext();
                    }
                    if (originalUserId != null) {
                        LoginUserInfoManager.setUserId(originalUserId);
                    } else {
                        LoginUserInfoManager.remove();
                    }
                }
            };
            // TTL 自动传递所有 TransmittableThreadLocal
            return TtlRunnable.get(wrapped);
        };
    }

    // ======================== 工具方法：创建线程池 ========================
    private ThreadPoolTaskExecutor buildExecutor(String prefix, int core, int max, int queue,
                                                 RejectedExecutionHandler rejectionHandler) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setKeepAliveSeconds(KEEP_ALIVE_SECONDS);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(rejectionHandler);
        executor.setTaskDecorator(taskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        executor.initialize();
        return executor;
    }

    // ======================== 业务线程池定义 ========================

    /**
     * 通用 IO 密集型池：用于记忆压缩、意图识别、搜索通道等轻逻辑
     */
    @Bean("ioBoundExecutor")
    public ThreadPoolTaskExecutor ioBoundExecutor() {
        return buildExecutor("io-bound-", IO_CORE, IO_MAX, QUEUE_CAPACITY,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 用户请求处理池：同时作为 Spring 默认异步池
     */
    @Bean("taskUserExecutor")
    public ThreadPoolTaskExecutor taskUserExecutor() {
        return buildExecutor("user-task-", MIXED_CORE, MIXED_MAX, QUEUE_CAPACITY,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 文件上传专用池（IO 极重）
     */
    @Bean("uploadExecutor")
    public ThreadPoolTaskExecutor uploadExecutor() {
        int uploadCore = Math.max(CPU_COUNT / 2, 2);
        int uploadMax = Math.max(CPU_COUNT * 2, 8);
        return buildExecutor("upload-", uploadCore, uploadMax, 500,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    // 兼容旧 Bean 名称
    @Bean("userExecutor")
    public ThreadPoolTaskExecutor userExecutor() {
        return taskUserExecutor();
    }

    @Bean("memeryExecutor")
    public ThreadPoolTaskExecutor memeryExecutor() {
        return ioBoundExecutor();
    }

    @Bean("summaryExecutor")
    public ThreadPoolTaskExecutor summaryExecutor() {
        return ioBoundExecutor();
    }

    @Bean("mcpExecutor")
    public ThreadPoolTaskExecutor mcpExecutor() {
        return ioBoundExecutor();
    }

    @Bean("neo4jExecutor")
    public ThreadPoolTaskExecutor neo4jExecutor() {
        return ioBoundExecutor();
    }

    @Bean("searchChannelExecutor")
    public ThreadPoolTaskExecutor searchChannelExecutor() {
        return ioBoundExecutor();
    }

    @Bean("intentExecutor")
    public ThreadPoolTaskExecutor intentExecutor() {
        return ioBoundExecutor();
    }

    @Bean("graphExecutor")
    public ThreadPoolTaskExecutor graphExecutor() {
        return ioBoundExecutor();
    }

    @Bean("milvusExecutor")
    public ThreadPoolTaskExecutor milvusExecutor() {
        return ioBoundExecutor();
    }

    @Bean("chatExecutor")
    public ThreadPoolTaskExecutor chatExecutor() {
        return ioBoundExecutor();
    }

    /**
     * 链路追踪记录专用线程池（轻量级）
     */
    @Bean("traceExecutor")
    public ThreadPoolTaskExecutor traceExecutor() {
        return buildExecutor("trace-", 2, 4, 200,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * 系统评估专用线程池
     */
    @Bean("evaluateExecutor")
    public ThreadPoolTaskExecutor evaluateExecutor() {
        return buildExecutor("eval-", 2, 4, 100,
                (r, e) -> log.warn("评估任务被丢弃"));
    }
}