package com.XYai.myai.config;

import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Web MVC 配置类，统一定义接口跨域访问策略。
 */
@Configuration
public class MvcConfiguration implements WebMvcConfigurer {

    @Resource
    private TaskDecorator userContextDecorator;

    /**
     * 配置专门用于处理 Spring MVC 异步请求（如 Flux / SSE 流式输出）的线程池，
     * 解决 'This executor is not suitable for production use under load' 的警告。
     */
    @Bean
    public SimpleAsyncTaskExecutor mvcTaskExecutor() {
        SimpleAsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
        executor.setVirtualThreads(true);
        executor.setThreadNamePrefix("MvcAsync-");
        executor.setTaskDecorator(userContextDecorator);
        return executor;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/xyAdmin/**")
                .addResourceLocations("classpath:/static/xyAdmin/")
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected org.springframework.core.io.Resource getResource(
                            String resourcePath, org.springframework.core.io.Resource location) {
                        try {
                            org.springframework.core.io.Resource resource = location.createRelative(resourcePath);
                            if (resource.exists() && resource.isReadable()) {
                                return resource;
                            }
                        } catch (Exception e) {
                            // fall through to index.html fallback
                        }
                        try {
                            return location.createRelative("index.html");
                        } catch (Exception e) {
                            return null;
                        }
                    }
                });
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
