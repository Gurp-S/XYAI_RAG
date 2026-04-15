package com.XYai.myai.security.service;

import io.jsonwebtoken.Claims;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * JWT 服务接口：
 * 提供生成、验证以及从 token 中解析信息的抽象方法。
 * 所有实现应负责使用配置的密钥对 token 进行签名/验签，并在必要时处理 token 的撤销/黑名单逻辑。
 */
public interface JwtService {

    /**
     * 为已认证的用户生成访问令牌（access token）。
     *
     * @param user 已认证的用户信息，用于设置 subject 及 claims（例如角色）
     * @param jti  本次 token 的唯一 id（可用于撤销或黑名单检查）
     * @return 签名后的 JWT 字符串
     */
    String generateAccessToken(UserDetails user, String jti);

    /**
     * 验证访问令牌是否合法：签名是否正确、是否过期等。实现可以额外检查 jti 是否被撤销。
     *
     * @param token 要验证的 JWT 字符串
     * @return 若合法返回 true，否则返回 false
     */
    boolean validateAccessToken(String token);

    /**
     * 从 token 中提取用户名（subject）。如果 token 无效则返回 null。
     *
     * @param token JWT 字符串
     * @return subject（通常为用户名或用户 id），或 null
     */
    String extractUsername(String token);

    /**
     * 解析并返回 token 的所有 claims。若 token 无效则返回 null。
     *
     * @param token JWT 字符串
     * @return Claims 对象或 null
     */
    Claims extractAllClaims(String token);

    /**
     * 从 token 中读取 jti（JWT ID）声明，用于标识/撤销 token。若 token 无效则返回 null。
     *
     * @param token JWT 字符串
     * @return jti 字符串或 null
     */
    String getJti(String token);

}