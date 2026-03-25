package com.XYai.myai.Config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置类，统一定义接口跨域访问策略。
 */
@Configuration
public class MvcConfiguration implements WebMvcConfigurer {

    /**
     * 注册全局跨域规则，允许常见 HTTP 方法与任意请求头。
     *
     * @param registry Spring MVC 跨域注册器
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedMethods("GET","POST","PUT","DELETE","OPTIONS")
                .allowedHeaders("*");
    }
}
