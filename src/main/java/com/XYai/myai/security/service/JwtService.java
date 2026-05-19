package com.XYai.myai.security.service;

import org.springframework.security.core.userdetails.UserDetails;

public interface JwtService {

    String generateAccessToken(UserDetails user, String jti);
}
