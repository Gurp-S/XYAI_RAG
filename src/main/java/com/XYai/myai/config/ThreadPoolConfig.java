package com.XYai.myai.config;

import com.XYai.myai.user.LoginUserInfoManager;
import com.alibaba.ttl.TtlRunnable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

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

    @Bean("userExecutor")
    public SimpleAsyncTaskExecutor userExecutor() {
        return taskUserExecutor();
    }

    @Bean("mcpExecutor")
    public SimpleAsyncTaskExecutor mcpExecutor() {
        return ioBoundExecutor();
    }

    @Bean("evaluateExecutor")
    public SimpleAsyncTaskExecutor evaluateExecutor() {
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
}