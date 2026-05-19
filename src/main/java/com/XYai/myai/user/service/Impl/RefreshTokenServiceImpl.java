package com.XYai.myai.user.service.Impl;

import com.XYai.myai.mapper.RefreshTokenMapper;
import com.XYai.myai.user.pojo.RefreshToken;
import com.XYai.myai.user.service.RefreshTokenService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.apache.commons.codec.binary.Hex;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * RefreshToken 业务层实现类
 * 功能：处理刷新令牌（RefreshToken）的存储、查询、吊销等核心逻辑
 */
@Service
public class RefreshTokenServiceImpl implements RefreshTokenService {

    /**
     * 注入 RefreshToken 数据库操作 Mapper
     */
    @Resource
    private RefreshTokenMapper refreshTokenMapper;

    /**
     * 保存刷新令牌到数据库
     *
     * @param userId    用户ID
     * @param tokenHash 刷新令牌的哈希值（不存明文）
     * @param issuedAt  令牌签发时间
     * @param expiresAt 令牌过期时间
     *                  事务注解：保证数据库操作原子性
     */
    @Override
    @Transactional
    public void saveRefreshToken(Long userId, String tokenHash, LocalDateTime issuedAt, LocalDateTime expiresAt) {
        // 构建 RefreshToken 实体对象
        RefreshToken t = new RefreshToken();
        t.setUserId(userId);            // 设置用户ID
        t.setTokenHash(tokenHash);      // 设置令牌哈希
        t.setIssuedAt(issuedAt);        // 设置签发时间
        t.setExpiresAt(expiresAt);      // 设置过期时间
        t.setRevoked(false);            // 初始状态：未吊销

        // 插入数据库
        refreshTokenMapper.insert(t);
    }

    /**
     * 根据令牌哈希值查询 RefreshToken
     *
     * @param hash 令牌哈希
     * @return 返回 Optional 包装的 RefreshToken，避免空指针
     */
    @Override
    public Optional<RefreshToken> findByTokenHash(String hash) {
        RefreshToken t = refreshTokenMapper.selectByTokenHash(hash);
        return Optional.ofNullable(t);
    }

    /**
     * 根据令牌哈希值 吊销 RefreshToken
     *
     * @param hash 令牌哈希
     *             逻辑：将 revoked 字段设为 true，表示令牌失效
     */
    @Override
    @Transactional
    public void revokeByHash(String hash) {
        // 根据哈希查询令牌
        RefreshToken t = refreshTokenMapper.selectByTokenHash(hash);
        // 存在则标记为已吊销
        if (t != null) {
            t.setRevoked(true);
            refreshTokenMapper.updateById(t);
        }
    }

    /**
     * 吊销某个用户的 所有 RefreshToken
     *
     * @param userId 用户ID
     *               场景：用户修改密码、退出登录、强制下线时使用
     */
    @Override
    @Transactional
    public void revokeAllForUser(Long userId) {
        // 构建 MyBatis-Plus 查询条件：userId = ?
        LambdaQueryWrapper<RefreshToken> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(RefreshToken::getUserId, userId);

        // 查询该用户的所有刷新令牌
        List<RefreshToken> list = refreshTokenMapper.selectList(wrapper);

        // 遍历并全部标记为已吊销
        if (list != null) {
            for (RefreshToken t : list) {
                t.setRevoked(true);
                refreshTokenMapper.updateById(t);
            }
        }
    }

    /**
     * 生成安全随机令牌 + 令牌哈希加密（用于 RefreshToken 安全存储）
     */
    @Override
    public String generateSecureRandomToken() {
        // 1. 创建48字节长度的字节数组，用于存储随机数
        // 48字节 = 384位，随机强度极高，无法被暴力破解
        byte[] bytes = new byte[48];

        // 2. 创建安全随机数生成器（SecureRandom 是加密级安全，比 Random 更安全）
        SecureRandom rnd = new SecureRandom();

        // 3. 生成随机字节并填充到数组中
        rnd.nextBytes(bytes);

        // 4. 使用 URL 安全的 Base64 编码（无填充），生成最终的随机令牌字符串
        // URL 安全：避免 + / = 等特殊字符，适合放在 URL / Token 中
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * 对原始令牌进行 SHA-256 哈希加密（数据库只存哈希，不存明文，更安全）
     *
     * @param token 原始随机令牌（generateSecureRandomToken 生成的字符串）
     * @return 哈希后的十六进制字符串（存入数据库）
     */
    @Override
    public String hashTokenSHA256(String token) {
        // 1. 获取 SHA-256 加密算法实例
        MessageDigest md = null;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 算法不可用", e);
        }

        // 2. 对令牌字符串进行 UTF-8 编码后，执行哈希计算
        byte[] digest = md.digest(token.getBytes(StandardCharsets.UTF_8));

        // 3. 将哈希后的字节数组转为 十六进制字符串（方便数据库存储）
        return Hex.encodeHexString(digest);
    }
}