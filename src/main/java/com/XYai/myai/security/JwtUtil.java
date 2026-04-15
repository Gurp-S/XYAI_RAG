package com.XYai.myai.security;

import com.XYai.myai.security.POJO.JwtProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.Optional;

/**
 * JWT 辅助工具类（轻量）：
 * <p>
 * 主要职责：
 * 1. 从 HTTP 请求中解析 access token（通常位于 Authorization header 的 Bearer 部分）；
 * 2. 从 cookie 中读取/写入 refresh token（cookie 名称由配置决定）；
 * <p>
 * 说明：本类不做 token 验证/签名工作，仅负责 I/O（读取 header/cookie、写 cookie）。
 */
public final class JwtUtil {

	private JwtUtil() {}

	/**
	 * 从请求的 header 中解析 access token（会自动处理前缀，例如 "Bearer"）。
	 *
	 * @param request HttpServletRequest
	 * @param props   JwtProperties（包含 header 名称与前缀配置）
	 * @return 如果找到则返回 token，否则返回 Optional.empty()
	 */
	public static Optional<String> resolveAccessTokenFromHeader(HttpServletRequest request, JwtProperties props) {
		String header = request.getHeader(props.getHeader());
		if (header == null) return Optional.empty();
		// JwtProperties.prefix 可能没有尾部空格，因此不在此处默认附加空格
		String prefix = props.getPrefix() == null ? "Bearer" : props.getPrefix();
		if (header.startsWith(prefix)) {
			return Optional.of(header.substring(prefix.length()).trim());
		}
		return Optional.empty();
	}

	/**
	 * 从请求的 cookies 中查找 refresh token（cookie 名称从配置读取）。
	 *
	 * @param request HttpServletRequest
	 * @param props   JwtProperties
	 * @return refresh token 值或 Optional.empty()
	 */
	public static Optional<String> resolveRefreshTokenFromCookie(HttpServletRequest request, JwtProperties props) {
		if (request.getCookies() == null) return Optional.empty();
		String name = props.getRefreshCookieName();
		if (name == null || name.isBlank()) return Optional.empty();
		for (Cookie c : request.getCookies()) {
			if (name.equals(c.getName())) return Optional.ofNullable(c.getValue());
		}
		return Optional.empty();
	}

	/**
	 * 在响应中设置 refresh token 的 cookie。注意：生产环境下请启用 secure=true 并设置合适的 SameSite 策略。
	 *
	 * @param response      HttpServletResponse
	 * @param props         JwtProperties（用于获取 cookie 名称）
	 * @param token         refresh token 字符串
	 * @param maxAgeSeconds cookie 最大生存时间（秒）
	 */
	public static void setRefreshTokenCookie(HttpServletResponse response, JwtProperties props, String token, int maxAgeSeconds) {
		if (props.getRefreshCookieName() == null || props.getRefreshCookieName().isBlank()) return;
		Cookie cookie = new Cookie(props.getRefreshCookieName(), token);
		boolean secure = Boolean.TRUE.equals(props.getHttpSecure());
		// 对刷新 token 使用 HttpOnly，避免 JS 读取
		cookie.setHttpOnly(true);
		cookie.setPath("/");
		// 使用传入的 maxAgeSeconds 参数（以便测试或调用方覆盖）
		cookie.setMaxAge(maxAgeSeconds);
		// secure 策略由配置决定，便于本地 HTTP 与线上 HTTPS 共存
		cookie.setSecure(secure);
		response.addCookie(cookie);
	}

	/**
	 * 清除 refresh token cookie（设置空值并将 maxAge 置为 0）。
	 */
	public static void clearRefreshTokenCookie(HttpServletResponse response, JwtProperties props) {
		if (props.getRefreshCookieName() == null || props.getRefreshCookieName().isBlank()) return;
		Cookie cookie = new Cookie(props.getRefreshCookieName(), "");
		boolean secure = Boolean.TRUE.equals(props.getHttpSecure());
		cookie.setHttpOnly(true);
		cookie.setPath("/");
		cookie.setMaxAge(0);
		cookie.setSecure(secure);
		response.addCookie(cookie);
	}
}
