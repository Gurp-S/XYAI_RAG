package com.XYai.myai.security.service.Impl;

import com.XYai.myai.security.POJO.JwtProperties;
import com.XYai.myai.security.service.JwtService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.stream.Collectors;

/**
 * JWT 服务实现（基于 RSA 签名的 JWT）：
 *
 * 实现要点：
 * - 应用启动时从配置的 PEM 文件加载私钥/公钥（PKCS#8 / X.509 格式），用于签名/验签；
 * - generateAccessToken：为用户生成包含 subject（用户名或用户 ID）、jti、issuer、issuedAt、expiration 和角色列表的 JWT；
 * - validateAccessToken / extractAllClaims：使用公钥验签并解析 claims；
 * - 提示：生产环境中应当对私钥加密存储，并对 jti 做黑名单/撤销检查（比如放 Redis）。
 */
@Service
public class JwtServiceImpl implements JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtServiceImpl.class);

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final JwtProperties props;
    private PrivateKey privateKey;
    private PublicKey publicKey;

    // 构造器注入 JwtProperties
    public JwtServiceImpl(JwtProperties props) {
        this.props = props;
    }

    // 启动时加载密钥（PEM PKCS#8 / X.509）
    @PostConstruct
    public void init() {
        try {
            String privPath = props.getRsaPrivateKeyPath();
            String pubPath = props.getRsaPublicKeyPath();
            if (privPath != null && !privPath.isBlank() && pubPath != null && !pubPath.isBlank()) {
                java.nio.file.Path pPriv = Paths.get(privPath);
                java.nio.file.Path pPub = Paths.get(pubPath);
                if (Files.exists(pPriv) && Files.exists(pPub)) {
                    this.privateKey = loadPrivateKey(privPath);
                    this.publicKey = loadPublicKey(pubPath);
                    log.info("Loaded RSA keys from configured paths");
                    return;
                } else {
                    log.warn("Configured RSA key files not found (private: {}, public: {}), falling back to ephemeral key pair.", privPath, pubPath);
                }
            } else {
                log.warn("RSA key paths not configured (jwt.rsa-private-key-path / jwt.rsa-public-key-path). Using ephemeral RSA key pair for development/testing.");
            }

            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            this.privateKey = kp.getPrivate();
            this.publicKey = kp.getPublic();
            log.warn("Generated ephemeral RSA key pair for JWT (not suitable for production)");
        } catch (Exception e) {
            throw new RuntimeException("加载 JWT 密钥失败", e);
        }
    }

    private PrivateKey loadPrivateKey(String pemPath) throws Exception {
        // 从磁盘读取 PEM 文件内容（PKCS#8 私钥），然后去掉头尾并 base64 解码得到 DER 二进制
        // 注意：pemPath 可能是文件系统路径，也可以增强为支持 classpath 资源
        String pem = Files.readString(Paths.get(pemPath), StandardCharsets.UTF_8);
        String base64 = stripPemHeaders(pem);
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }

    private PublicKey loadPublicKey(String pemPath) throws Exception {
        // 从磁盘读取 X.509 公钥 PEM，去掉头尾并 base64 解码得到 DER，再生成 PublicKey
        String pem = Files.readString(Paths.get(pemPath), StandardCharsets.UTF_8);
        String base64 = stripPemHeaders(pem);
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private String stripPemHeaders(String pem) {
        // 移除 PEM 文件头部/尾部与所有空白字符，返回纯 base64 内容
        return pem.replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----", "")
                .replaceAll("\\s", "");
    }

    private java.util.List<String> collectRoles(UserDetails user) {
        // 将 Spring Security 的 GrantedAuthority 列表映射为字符串列表（例如 ROLE_USER、ROLE_ADMIN）
        return user.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toList());
    }

    @Override
    public String generateAccessToken(UserDetails user, String jti) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + props.getAccessTokenExpSec() * 1000L);
        return Jwts.builder()
                // subject 一般为用户名或用户 id（本项目中使用 username 字段）
                .setSubject(user.getUsername())
                .setId(jti)
                .setIssuer(props.getIssuer())
                .setIssuedAt(now)
                .setExpiration(exp)
                // 自定义 claim：将角色以字符串数组的形式保存，便于在下游鉴权使用
                .claim("roles", collectRoles(user))
                // 使用私钥按 RS256 算法进行签名，返回 compact 表示的 JWT 字符串
                .signWith(privateKey, SignatureAlgorithm.RS256)
                .compact();
    }

    @Override
    public boolean validateAccessToken(String token) {
        try {
            String jti = getJti(token);
            // 检查 Redis 黑名单（示例用 stringRedisTemplate）
            Boolean black = stringRedisTemplate.hasKey("jwt:blacklist:" + jti);
            return !black;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }


    @Override
    public Claims extractAllClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(publicKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            return null;
        }
    }

    @Override
    public String extractUsername(String token) {
        Claims c = extractAllClaims(token);
        return c == null ? null : c.getSubject();
    }

    @Override
    public String getJti(String token) {
        Claims c = extractAllClaims(token);
        return c == null ? null : c.getId();
    }
}