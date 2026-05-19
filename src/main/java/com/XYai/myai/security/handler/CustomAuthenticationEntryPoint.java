package com.XYai.myai.security.handler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

public class CustomAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private static final Logger log = LoggerFactory.getLogger(CustomAuthenticationEntryPoint.class);

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        if (response.isCommitted()) {
            log.warn("Response already committed, skipping entry point for: {}", request.getRequestURI());
            return;
        }
        log.debug("Authentication failed for {}: {}", request.getRequestURI(), authException.getMessage());
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        String body = String.format("{\"error\":\"unauthorized\",\"message\":\"%s\"}",
                authException.getMessage() != null
                        ? authException.getMessage().replace("\"", "\\\"")
                        : "Authentication required");
        response.getWriter().write(body);
        response.getWriter().flush();
    }
}
