package com.XYai.myai.security.config;

import com.XYai.myai.security.pojo.JwtProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Configuration
public class JwtConfig {

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties props, StringRedisTemplate stringRedisTemplate) throws Exception {
        Resource publicKeyResource = props.getRsaPublicKeyPath();
        if (publicKeyResource == null || !publicKeyResource.exists()) {
            throw new IllegalStateException("RSA public key not configured");
        }
        RSAPublicKey publicKey = (RSAPublicKey) loadPublicKey(publicKeyResource);
        return new RsaJwtDecoder(publicKey, stringRedisTemplate);
    }

    private PublicKey loadPublicKey(Resource resource) throws Exception {
        String pem = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        String base64 = pem.replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
    }

    private static class RsaJwtDecoder implements JwtDecoder {

        private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

        private final RSAPublicKey publicKey;
        private final StringRedisTemplate stringRedisTemplate;

        RsaJwtDecoder(RSAPublicKey publicKey, StringRedisTemplate stringRedisTemplate) {
            this.publicKey = publicKey;
            this.stringRedisTemplate = stringRedisTemplate;
        }

        @Override
        public Jwt decode(String token) throws JwtException {
            io.jsonwebtoken.Claims claims;
            try {
                claims = io.jsonwebtoken.Jwts.parser()
                        .verifyWith(publicKey)
                        .build()
                        .parseSignedClaims(token)
                        .getPayload();
            } catch (io.jsonwebtoken.ExpiredJwtException e) {
                claims = e.getClaims();
            } catch (io.jsonwebtoken.JwtException e) {
                throw new JwtException("JWT verification failed: " + e.getMessage(), e);
            }

            String jti = claims.getId();
            if (jti != null && Boolean.TRUE.equals(stringRedisTemplate.hasKey(BLACKLIST_PREFIX + jti))) {
                throw new JwtException("JWT has been revoked (blacklisted jti: " + jti + ")");
            }

            Instant iat = claims.getIssuedAt() != null
                    ? claims.getIssuedAt().toInstant()
                    : Instant.EPOCH;
            Instant exp = claims.getExpiration() != null
                    ? claims.getExpiration().toInstant()
                    : Instant.MAX;

            Map<String, Object> headers = Map.of("alg", "RS256");
            return new Jwt(token, iat, exp, headers, claims);
        }
    }
}
