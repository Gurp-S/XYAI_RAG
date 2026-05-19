package com.XYai.myai.security;

import com.XYai.myai.security.pojo.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Optional;

public final class JwtUtil {

    private JwtUtil() {
    }

    public static Optional<String> resolveRefreshTokenFromCookie(HttpServletRequest request, JwtProperties props) {
        if (request.getCookies() == null) return Optional.empty();
        String name = props.getRefreshCookieName();
        if (name == null || name.isBlank()) return Optional.empty();
        for (Cookie c : request.getCookies()) {
            if (name.equals(c.getName())) return Optional.ofNullable(c.getValue());
        }
        return Optional.empty();
    }

    public static void setRefreshTokenCookie(HttpServletResponse response, JwtProperties props, String token, int maxAgeSeconds) {
        if (props.getRefreshCookieName() == null || props.getRefreshCookieName().isBlank()) return;
        Cookie cookie = new Cookie(props.getRefreshCookieName(), token);
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(maxAgeSeconds);
        cookie.setSecure(props.isHttpSecure());
        response.addCookie(cookie);
    }

    public static void clearRefreshTokenCookie(HttpServletResponse response, JwtProperties props) {
        if (props.getRefreshCookieName() == null || props.getRefreshCookieName().isBlank()) return;
        Cookie cookie = new Cookie(props.getRefreshCookieName(), "");
        cookie.setHttpOnly(true);
        cookie.setPath("/");
        cookie.setMaxAge(0);
        cookie.setSecure(props.isHttpSecure());
        response.addCookie(cookie);
    }
}
