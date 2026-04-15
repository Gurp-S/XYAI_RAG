package com.XYai.myai.user.POJO;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * RefreshToken 实体类
 * 对应数据库表：xy_refresh_token
 * 用途：存储用户的刷新令牌，用于 JWT 无感刷新登录状态
 */
@Data                  // 自动生成 getter、setter、toString、equals、hashCode
@NoArgsConstructor     // 无参构造器（MyBatis-Plus 必须）
@AllArgsConstructor    // 全参构造器
@TableName("xy_refresh_token")  // 绑定数据库表名
public class RefreshToken {

    /**
     * 主键 ID（自增）
     */
    @TableId(value = "id")
    private Long id;

    /**
     * 关联的用户 ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 刷新令牌的 SHA256 哈希值
     * 数据库只存哈希，不存明文 Token，保证安全
     */
    @TableField("token_hash")
    private String tokenHash;

    /**
     * 令牌签发时间
     */
    @TableField("issued_at")
    private LocalDateTime issuedAt;

    /**
     * 令牌过期时间
     */
    @TableField("expires_at")
    private LocalDateTime expiresAt;

    /**
     * 是否吊销
     * true = 已吊销（失效）
     * false = 有效
     */
    @TableField("revoked")
    private Boolean revoked;
}