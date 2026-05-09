package com.XYai.myai.config;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * IK分词器配置（备选方案）
 * 使用懒加载，避免启动时初始化问题
 */
@Slf4j
@Configuration
public class IKAnalyzerConfig {

    @Resource
    @Qualifier("ioBoundExecutor")   // 复用已有线程池
    private ThreadPoolTaskExecutor executor;

    @PostConstruct
    public void init() {
        // 使用 ioBoundExecutor 提交任务，自动传递上下文
        executor.submit(() -> {
            try {
                Thread.sleep(1000);
                Class.forName("org.wltea.analyzer.dic.Dictionary");
                log.info("IK分词器类加载完成");
            } catch (Exception e) {
                log.warn("IK分词器初始化跳过: {}", e.getMessage());
            }
        });
        log.info("IKAnalyzerConfig 初始化完成（懒加载模式）");
    }
}