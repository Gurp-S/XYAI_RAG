package com.XYai.myai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置类，统一定义接口跨域访问策略。
 */
@Configuration
public class MvcConfiguration implements WebMvcConfigurer {

    /**
     * 配置专门用于处理 Spring MVC 异步请求（如 Flux / SSE 流式输出）的线程池，
     * 解决 'This executor is not suitable for production use under load' 的警告。
     */
    @Bean
    public ThreadPoolTaskExecutor mvcTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(100);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("MvcAsync-");
        executor.initialize();
        return executor;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(mvcTaskExecutor());
        // 可以设置异步请求超时时间，例如 10 分钟 (防止长流意外断开一直等待)
        configurer.setDefaultTimeout(600000L);
    }

    /**
     * 注册全局跨域规则，允许常见 HTTP 方法与任意请求头。
     *
     * @param registry Spring MVC 跨域注册器
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }

}
