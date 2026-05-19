package com.XYai.myai.user.service;

import com.XYai.myai.user.pojo.RefreshToken;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RefreshTokenService {
    void saveRefreshToken(Long userId, String tokenHash, LocalDateTime issuedAt, LocalDateTime expiresAt);

    Optional<RefreshToken> findByTokenHash(String hash);

    void revokeByHash(String hash);

    void revokeAllForUser(Long userId);

    String generateSecureRandomToken();

    String hashTokenSHA256(String token);

}
