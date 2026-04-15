package com.XYai.myai.security.POJO;

import lombok.Data;
// removed unused imports
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

//    jwt:
//    issuer: XYai
//    access-token-exp-sec: 900        # 15 min
//    refresh-token-exp-sec: 1209600  # 14 days
//    refresh-cookie-name: refresh_token
//    header: Authorization
//    prefix: Bearer
    /**
     * JWT 签发者（issuer）标识，用于在 token 中设置 iss 声明
     * 配置项: jwt.issuer
     */
    private String issuer;

    /**
     * access token 的过期时间（秒）
     * 配置项: jwt.access-token-exp-sec
     */
    private long accessTokenExpSec;

    /**
     * refresh token 的过期时间（秒）
     * 配置项: jwt.refresh-token-exp-sec
     */
    private long refreshTokenExpSec;

    /**
     * 保存 refresh token 的 cookie 名称（若为空则不使用 cookie 存储）
     * 配置项: jwt.refresh-cookie-name
     */
    private String refreshCookieName;

    /**
     * HTTP header 名称，默认 Authorization
     * 配置项: jwt.header
     */
    private String header = "Authorization";

    /**
     * Authorization header 的前缀（例如 Bearer），注意不包含尾部空格，读取 header 时会 trim()
     * 配置项: jwt.prefix
     */
    private String prefix = "Bearer";

    /**
     * RSA 私钥文件路径（PEM PKCS#8），用于对 access token 签名
     * 配置项: jwt.rsa-private-key-path
     */
    private String rsaPrivateKeyPath;

    /**
     * RSA 公钥文件路径（PEM X.509），用于验证 access token 签名
     * 配置项: jwt.rsa-public-key-path
     */
    private String rsaPublicKeyPath;


    /**
     * HTTPS，cookie 是否被浏览器保存
     */
    private Boolean httpSecure;
}