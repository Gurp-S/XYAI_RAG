package com.XYai.myai.config;

import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.POJO.User;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 全局线程池配置 + 实时监控端点
 * 监控接口：/actuator/thread-pool 或 /thread-pool
 */
@Configuration
public class ThreadPoolConfig {

    private static final int DEFAULT_MAX_POOL_SIZE = 12;
    private static final int DEFAULT_QUEUE_CAPACITY = 100;
    private static final int DEFAULT_KEEP_ALIVE_SECONDS = 60;

    @Bean("userExecutor")
    public ThreadPoolTaskExecutor userExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int cpuCount = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(Math.min(cpuCount, DEFAULT_MAX_POOL_SIZE));
        executor.setMaxPoolSize(DEFAULT_MAX_POOL_SIZE);
        executor.setQueueCapacity(DEFAULT_QUEUE_CAPACITY);
        executor.setKeepAliveSeconds(DEFAULT_KEEP_ALIVE_SECONDS);
        executor.setThreadNamePrefix("user-thread-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 上下文传递
        executor.setTaskDecorator(userContextDecorator());

        executor.initialize();
        return executor;
    }

    @Bean("memeryExecutor")
    public ThreadPoolTaskExecutor memeryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int cpuCount = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(Math.min(cpuCount, DEFAULT_MAX_POOL_SIZE));
        executor.setMaxPoolSize(DEFAULT_MAX_POOL_SIZE);
        executor.setQueueCapacity(DEFAULT_QUEUE_CAPACITY);
        executor.setKeepAliveSeconds(DEFAULT_KEEP_ALIVE_SECONDS);
        executor.setThreadNamePrefix("memory-thread-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 上下文传递
        executor.setTaskDecorator(userContextDecorator());

        executor.initialize();
        return executor;
    }

    @Bean("uploadExecutor")
    public ThreadPoolTaskExecutor uploadExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int cpuCount = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(Math.min(cpuCount, DEFAULT_MAX_POOL_SIZE));
        executor.setMaxPoolSize(DEFAULT_MAX_POOL_SIZE);
        executor.setQueueCapacity(200);
        executor.setKeepAliveSeconds(DEFAULT_KEEP_ALIVE_SECONDS);
        executor.setThreadNamePrefix("upload-thread-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 上下文传递
        executor.setTaskDecorator(userContextDecorator());

        executor.initialize();
        return executor;
    }

    @Bean("searchChannelExecutor")
    public ThreadPoolTaskExecutor searchChannelExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int cpuCount = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(Math.min(cpuCount, DEFAULT_MAX_POOL_SIZE));
        executor.setMaxPoolSize(DEFAULT_MAX_POOL_SIZE);
        executor.setQueueCapacity(DEFAULT_QUEUE_CAPACITY);
        executor.setKeepAliveSeconds(DEFAULT_KEEP_ALIVE_SECONDS);
        executor.setThreadNamePrefix("search-channel-thread-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 上下文传递
        executor.setTaskDecorator(userContextDecorator());

        executor.initialize();
        return executor;
    }

    /**
     * 为了避免 Spring 在启用异步时找不到名为 `taskExecutor` 的默认 bean，提供一个别名指向 `userExecutor`。
     * 这样能消除 "More than one TaskExecutor bean found and none named 'taskExecutor'" 的警告。
     */
    @Bean("taskExecutor")
    public Executor taskExecutor() {
        return userExecutor();
    }

    /**
     * 统一用户上下文装饰器：在异步线程之间传递 LoginUserInfoManager
     */
    private TaskDecorator userContextDecorator() {
        return runnable -> {
            // 拿到主线程的用户
            User user = LoginUserInfoManager.get();

            return () -> {
                try {
                    // 塞入异步线程上下文
                    if (user != null) {
                        LoginUserInfoManager.set(user);
                    }
                    // 执行任务
                    runnable.run();
                } finally {
                    // 必须清理，防止线程复用导致串用户
                    LoginUserInfoManager.remove();
                }
            };
        };
    }
}