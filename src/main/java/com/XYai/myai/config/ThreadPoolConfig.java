package com.XYai.myai.config;

import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.POJO.User;
import com.alibaba.ttl.TtlRunnable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class ThreadPoolConfig {

    // ======================== 通用参数（可根据实际负载调整） ========================
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    // IO 密集型：线程数可设为 CPU 核数 * 2（理论值，需结合实际性能测试）
    private static final int IO_CORE = Math.max(CPU_COUNT * 2, 4);
    private static final int IO_MAX  = Math.max(CPU_COUNT * 3, 12);
    // 混合型（兼顾 IO 与计算）
    private static final int MIXED_CORE = CPU_COUNT;
    private static final int MIXED_MAX  = CPU_COUNT * 2;

    private static final int QUEUE_CAPACITY = 200;         // 适当增大缓冲
    private static final int KEEP_ALIVE_SECONDS = 120;     // 非核心线程存活时间
    private static final int AWAIT_TERMINATION_SECONDS = 60;

    // ======================== 统一装饰器（单例，减少重复创建） ========================
    @Bean
    public TaskDecorator userContextDecorator() {
        return runnable -> {
            User appUser = LoginUserInfoManager.get();
            SecurityContext securityContext = SecurityContextHolder.getContext();
            Runnable ttlRunnable = TtlRunnable.get(runnable);

            return () -> {
                SecurityContext originalContext = SecurityContextHolder.getContext();
                try {
                    if (securityContext != null && securityContext.getAuthentication() != null) {
                        SecurityContextHolder.setContext(securityContext);
                    }
                    if (appUser != null) {
                        LoginUserInfoManager.set(appUser);
                    }
                    ttlRunnable.run();
                } finally {
                    LoginUserInfoManager.remove();
                    SecurityContextHolder.setContext(originalContext);
                }
            };
        };
    }

    // ======================== 业务线程池定义 ========================

    /**
     * 通用 IO 密集型池：用于记忆压缩、意图识别、搜索通道等轻逻辑（原 memory / intent / search 合并）
     */
    @Bean("ioBoundExecutor")
    public ThreadPoolTaskExecutor ioBoundExecutor(TaskDecorator userContextDecorator) {
        return buildExecutor("io-bound-", IO_CORE, IO_MAX, QUEUE_CAPACITY,
                new ThreadPoolExecutor.CallerRunsPolicy(), userContextDecorator);
    }

    /**
     * 用户请求处理池：同时作为 Spring 默认异步池，避免与 @Async 混用造成资源争抢
     * 保留独立名称，方便日志追踪。
     */
    @Bean("taskUserExecutor")
    public ThreadPoolTaskExecutor taskExecutor(TaskDecorator userContextDecorator) {
        // 用户请求通常混合 IO/CPU，但偏 IO，可使用混合参数
        ThreadPoolTaskExecutor executor = buildExecutor("user-task-", MIXED_CORE, MIXED_MAX, QUEUE_CAPACITY,
                new ThreadPoolExecutor.CallerRunsPolicy(), userContextDecorator);
        // 指定为 Spring 默认异步执行器
        executor.setThreadNamePrefix("task-");
        return executor;
    }

    /**
     * 文件上传专用池（IO 极重，队列适当加大，拒绝策略改为 CallerRuns 保证不丢）
     */
    @Bean("uploadExecutor")
    public ThreadPoolTaskExecutor uploadExecutor(TaskDecorator userContextDecorator) {
        int uploadCore = Math.max(CPU_COUNT / 2, 2);
        int uploadMax = Math.max(CPU_COUNT * 2, 8);
        // 文件上传任务耗时长，队列可更大
        return buildExecutor("upload-", uploadCore, uploadMax, 500,
                new ThreadPoolExecutor.CallerRunsPolicy(), userContextDecorator);
    }

    // 如果需要保留旧 Bean 名称以兼容已有注入，可添加别名
    @Bean("userExecutor")
    public ThreadPoolTaskExecutor userExecutor(@Qualifier("taskUserExecutor") ThreadPoolTaskExecutor taskExecutor) {
        return taskExecutor;   // 直接指向 taskExecutor
    }

    @Bean("memeryExecutor")
    public ThreadPoolTaskExecutor memeryExecutor(@Qualifier("ioBoundExecutor") ThreadPoolTaskExecutor ioBoundExecutor) {
        return ioBoundExecutor;
    }

    @Bean("mcpExecutor")
    public ThreadPoolTaskExecutor mcpExecutor(@Qualifier("ioBoundExecutor") ThreadPoolTaskExecutor ioBoundExecutor) {
        return ioBoundExecutor;
    }

    @Bean("searchChannelExecutor")
    public ThreadPoolTaskExecutor searchChannelExecutor(@Qualifier("ioBoundExecutor") ThreadPoolTaskExecutor ioBoundExecutor) {
        return ioBoundExecutor;
    }

    @Bean("intentExecutor")
    public ThreadPoolTaskExecutor intentExecutor(@Qualifier("ioBoundExecutor") ThreadPoolTaskExecutor ioBoundExecutor) {
        return ioBoundExecutor;
    }

    @Bean("chatExecutor")
    public ThreadPoolTaskExecutor chatExecutor(@Qualifier("ioBoundExecutor") ThreadPoolTaskExecutor ioBoundExecutor) {
        return ioBoundExecutor;
    }

    // ======================== 工具方法 ========================
    private ThreadPoolTaskExecutor buildExecutor(String prefix, int core, int max, int queue,
                                                 RejectedExecutionHandler rejectionHandler,
                                                 TaskDecorator decorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setKeepAliveSeconds(KEEP_ALIVE_SECONDS);
        executor.setThreadNamePrefix(prefix);
        executor.setRejectedExecutionHandler(rejectionHandler);
        executor.setTaskDecorator(decorator);
        // 优雅停机设置
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_TERMINATION_SECONDS);
        executor.initialize();
        return executor;
    }
}