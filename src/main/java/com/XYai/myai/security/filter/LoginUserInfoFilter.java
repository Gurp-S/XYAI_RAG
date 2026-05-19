package com.XYai.myai.security.filter;

import com.XYai.myai.security.model.SecurityUser;
import com.XYai.myai.user.LoginUserInfoManager;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

public class LoginUserInfoFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            if (isExpired(jwt)) {
                SecurityContextHolder.clearContext();
            } else {
                Object rawUserId = jwt.getClaim("userId");
                if (rawUserId instanceof Number n) {
                    LoginUserInfoManager.setUserId(n.longValue());
                }
                String jti = jwt.getId();
                if (jti != null) {
                    LoginUserInfoManager.setJti(jti);
                }
                Instant exp = jwt.getExpiresAt();
                if (exp != null) {
                    LoginUserInfoManager.setTokenExp(exp.getEpochSecond());
                }
            }
        } else if (auth != null && auth.getPrincipal() instanceof SecurityUser su) {
            LoginUserInfoManager.setUserId(su.getUserId());
            LoginUserInfoManager.setUser(su.getUser());
        }
        try {
            filterChain.doFilter(request, response);
        } finally {
            LoginUserInfoManager.remove();
        }
    }

    private boolean isExpired(Jwt jwt) {
        Instant exp = jwt.getExpiresAt();
        return exp != null && exp.isBefore(Instant.now());
    }
}
