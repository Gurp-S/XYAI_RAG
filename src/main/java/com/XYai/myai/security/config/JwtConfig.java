package com.XYai.myai.security.config;

import com.XYai.myai.security.RsaKeyLoader;
import com.XYai.myai.security.pojo.JwtProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Map;

@Configuration
public class JwtConfig {

    @Bean
    public JwtDecoder jwtDecoder(JwtProperties props, StringRedisTemplate stringRedisTemplate) throws Exception {
        RSAPublicKey publicKey = (RSAPublicKey) RsaKeyLoader.loadPublicKey(
                props.getRsaPublicKey(), props.getRsaPublicKeyPath());
        return new RsaJwtDecoder(publicKey, stringRedisTemplate);
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
