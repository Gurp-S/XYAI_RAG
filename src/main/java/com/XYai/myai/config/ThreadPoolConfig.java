package com.XYai.myai.config;

import com.alibaba.ttl.TtlRunnable;
import com.XYai.myai.user.LoginUserInfoManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class ThreadPoolConfig {

    // ======================== 统一的 TaskDecorator ========================
    @Bean
    public TaskDecorator taskDecorator() {
        return runnable -> {
            SecurityContext securityContext = SecurityContextHolder.getContext();
            Long userId = LoginUserInfoManager.getUserId();

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
            return TtlRunnable.get(wrapped);
        };
    }

    // ======================== 虚拟线程执行器 ========================
    private SimpleAsyncTaskExecutor createVirtualExecutor(String prefix) {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
        executor.setVirtualThreads(true);
        executor.setThreadNamePrefix(prefix);
        executor.setTaskDecorator(taskDecorator());
        return executor;
    }

    @Bean("ioBoundExecutor")
    public SimpleAsyncTaskExecutor ioBoundExecutor() {
        return createVirtualExecutor("io-bound-");
    }

    @Bean("taskUserExecutor")
    public SimpleAsyncTaskExecutor taskUserExecutor() {
        return createVirtualExecutor("user-task-");
    }

    @Bean("uploadExecutor")
    public SimpleAsyncTaskExecutor uploadExecutor() {
        return createVirtualExecutor("upload-");
    }

    @Bean("userExecutor")
    public SimpleAsyncTaskExecutor userExecutor() {
        return taskUserExecutor();
    }

    @Bean("memeryExecutor")
    public SimpleAsyncTaskExecutor memeryExecutor() {
        return ioBoundExecutor();
    }

    @Bean("summaryExecutor")
    public SimpleAsyncTaskExecutor summaryExecutor() {
        return ioBoundExecutor();
    }

    @Bean("mcpExecutor")
    public SimpleAsyncTaskExecutor mcpExecutor() {
        return ioBoundExecutor();
    }

    @Bean("neo4jExecutor")
    public SimpleAsyncTaskExecutor neo4jExecutor() {
        return ioBoundExecutor();
    }

    @Bean("searchChannelExecutor")
    public SimpleAsyncTaskExecutor searchChannelExecutor() {
        return ioBoundExecutor();
    }

    @Bean("intentExecutor")
    public SimpleAsyncTaskExecutor intentExecutor() {
        return ioBoundExecutor();
    }

    @Bean("graphExecutor")
    public SimpleAsyncTaskExecutor graphExecutor() {
        return ioBoundExecutor();
    }

    @Bean("milvusExecutor")
    public SimpleAsyncTaskExecutor milvusExecutor() {
        return ioBoundExecutor();
    }

    @Bean("chatExecutor")
    public SimpleAsyncTaskExecutor chatExecutor() {
        return ioBoundExecutor();
    }

    @Bean("traceExecutor")
    public SimpleAsyncTaskExecutor traceExecutor() {
        return createVirtualExecutor("trace-");
    }

    @Bean("evaluateExecutor")
    public SimpleAsyncTaskExecutor evaluateExecutor() {
        return createVirtualExecutor("eval-");
    }

    // ======================== 异步DB写入线程池 ========================
    @Bean("traceDbExecutor")
    public ThreadPoolExecutor traceDbExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                2,                              // corePoolSize
                4,                              // maximumPoolSize — 不超过DB连接池上限
                60L,                            // keepAliveTime
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2048), // 有界队列防OOM
                r -> {
                    Thread t = new Thread(r);
                    t.setName("trace-db-pool");
                    t.setDaemon(true);
                    return t;
                },
                // 队列满时由提交线程直接执行，自然背压
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }
}