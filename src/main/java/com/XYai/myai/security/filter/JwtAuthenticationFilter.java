package com.XYai.myai.security.filter;

import com.XYai.myai.security.POJO.JwtProperties;
import com.XYai.myai.security.service.JwtService;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.POJO.User;
import com.XYai.myai.mapper.UserMapper;
import com.XYai.myai.user.service.CustomUserDetailsService;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
/*
  JWT 认证过滤器：
  责任：
  1. 从 HTTP Header 中读取 Authorization（或自定义 header）并解析 Bearer token；
  2. 使用 `JwtService` 验证 access token 的签名与有效期；
  3. 若验证通过，从 token 中提取用户名并使用 `CustomUserDetailsService` 加载 UserDetails，
     然后构造 Authentication 放入 SecurityContext（以便后续 Spring Security 能识别当前用户）；
  4. 将 domain 层的 User（如果 token subject 是用户 id）放到 `LoginUserInfoManager` 以便业务层直接使用；
  5. 该过滤器是无状态的（不操作 session），适用于 token-based 认证。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Resource
    private JwtService jwtService;

    @Resource
    private CustomUserDetailsService customUserDetailsService;

    @Resource
    private JwtProperties jwtProperties;

    @Resource
    private UserMapper userMapper;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response, @NonNull FilterChain chain)
            throws ServletException, IOException {
        // 线程可能被复用，先清理旧请求残留
        LoginUserInfoManager.remove();
        SecurityContextHolder.clearContext();

        // 1) 读取 header 并去除前缀（例如："Bearer <token>"）
        String header = request.getHeader(jwtProperties.getHeader());
        if (header != null && header.startsWith(jwtProperties.getPrefix())) {
            // 注意：JwtProperties.prefix 可能为 "Bearer"（无空格），因此 substring 时使用 prefix.length()
            String token = header.substring(jwtProperties.getPrefix().length()).trim();
            try {
                // 2) 验证 token
                if (jwtService.validateAccessToken(token)) {
                    // 3) 验证通过后解析用户名（或用户 id）并设置 Spring Security 上下文
                    String username = jwtService.extractUsername(token);
                    if (username != null) {
                        // 从持久层加载用户信息（包含权限），构造 Authentication 并存入上下文
                        UserDetails ud = customUserDetailsService.loadUserByUsername(username);
                        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(ud, null,
                                ud.getAuthorities());
                        SecurityContextHolder.getContext().setAuthentication(auth);

                        // 4) 将 domain 层的 User 放入线程上下文（LoginUserInfoManager）以便业务层直接取用
                        //    统一以 UserDetails.username（本项目为用户 id）反查 domain user
                        try {
                            Long uid = Long.parseLong(ud.getUsername());
                            User domain = userMapper.selectById(uid);
                            if (domain != null) {
                                LoginUserInfoManager.set(domain);
                            }
                        } catch (NumberFormatException ignore) {
                            // 如果 subject 不是数字 id，忽略此步
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("JWT 验证失败: {}", e.getMessage());
                LoginUserInfoManager.remove();
                SecurityContextHolder.clearContext();
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // 请求完成后统一清理，防止线程复用时串号
            LoginUserInfoManager.remove();
            SecurityContextHolder.clearContext();
        }
    }
}