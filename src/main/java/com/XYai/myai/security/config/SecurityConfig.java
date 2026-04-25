package com.XYai.myai.security.config;

import com.XYai.myai.security.filter.JwtAuthenticationFilter;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

@Configuration
/**
 * Spring Security 配置类：
 *
 * 主要职责：
 * - 配置安全过滤链（SecurityFilterChain），使用无状态的会话策略（STATELESS）适配 JWT；
 * - 配置哪些路径无需认证（如登录 /user/login、刷新 /user/refresh、静态资源等）；
 * - 将自定义的 `JwtAuthenticationFilter` 插入到 UsernamePasswordAuthenticationFilter
 * 之前，
 * 以便在请求到达 Controller 之前完成基于 JWT 的认证与 SecurityContext 的设置。
 */
@Slf4j
public class SecurityConfig {
    @Resource
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        // 使用 lambda DSL 保持与 Spring Security 6.x 的兼容性
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(securityContext -> securityContext
                        .securityContextRepository(new DelegatingSecurityContextRepository(
                                new RequestAttributeSecurityContextRepository(),
                                new HttpSessionSecurityContextRepository()
                        ))
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/user/login", "/user/registry", "/user/reset-password", "/user/refresh",
                                "/static/**", "/public/**", "/error")
                        .permitAll()
                        .anyRequest().authenticated());

        // 把 JWT 认证过滤器放在 UsernamePasswordAuthenticationFilter 之前，这样每次请求都会先由 JWT 过滤器
        // 尝试从 Authorization header 中解析并验证 token，再设置 SecurityContext
        log.info("jwt验证");
        http.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        // 使用自定义的 AccessDeniedHandler，避免在 response 已经被提交时尝试 forward/redirect 导致二次提交错误
        http.exceptionHandling(ex -> ex.accessDeniedHandler(new com.XYai.myai.security.handler.CustomAccessDeniedHandler()));

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }
}