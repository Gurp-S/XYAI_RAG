package com.XYai.myai.security.handler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * Custom AccessDeniedHandler that is safe when the response is already committed.
 * It writes a minimal JSON 403 body if the response is not committed, otherwise it logs and returns.
 */
public class CustomAccessDeniedHandler implements AccessDeniedHandler {
    private static final Logger log = LoggerFactory.getLogger(CustomAccessDeniedHandler.class);

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        if (response.isCommitted()) {
            log.warn("Cannot handle AccessDeniedException because response is already committed. Message: {}", accessDeniedException.getMessage());
            return;
        }

        try {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            String body = String.format("{\"error\":\"access_denied\",\"message\":\"%s\"}",
                    accessDeniedException.getMessage() == null ? "Access Denied" : accessDeniedException.getMessage().replace("\"", "\\\""));
            response.getWriter().write(body);
            response.getWriter().flush();
        } catch (IOException e) {
            log.error("Failed to write AccessDenied response", e);
            throw e;
        }
    }
}

