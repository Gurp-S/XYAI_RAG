package com.XYai.myai.security.service.Impl;

import com.XYai.myai.security.RsaKeyLoader;
import com.XYai.myai.security.model.SecurityUser;
import com.XYai.myai.security.pojo.JwtProperties;
import com.XYai.myai.security.service.JwtService;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.security.PrivateKey;
import java.util.Date;

@Service
public class JwtServiceImpl implements JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtServiceImpl.class);
    private final JwtProperties props;
    private final PrivateKey privateKey;

    public JwtServiceImpl(JwtProperties props) throws Exception {
        this.props = props;
        this.privateKey = RsaKeyLoader.loadPrivateKey(props.getRsaPrivateKey(), props.getRsaPrivateKeyPath());
        log.info("RSA private key loaded for JWT signing");
    }

    @Override
    public String generateAccessToken(UserDetails user, String jti) {
        Date now = new Date();
        Date exp = new Date(now.getTime() + props.getAccessTokenExpSec() * 1000L);

        SecurityUser su = (SecurityUser) user;
        return Jwts.builder()
                .subject(user.getUsername())
                .id(jti)
                .issuer(props.getIssuer())
                .issuedAt(now)
                .expiration(exp)
                .claim("roles", user.getAuthorities().stream()
                        .map(GrantedAuthority::getAuthority).toList())
                .claim("userId", su.getUserId())
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }
}
