package com.XYai.myai.security.service.Impl;

import com.XYai.myai.security.model.SecurityUser;
import com.XYai.myai.security.pojo.JwtProperties;
import com.XYai.myai.security.service.JwtService;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Date;

@Service
public class JwtServiceImpl implements JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtServiceImpl.class);
    private final JwtProperties props;
    private final PrivateKey privateKey;

    public JwtServiceImpl(JwtProperties props) throws Exception {
        this.props = props;
        Resource privateKeyResource = props.getRsaPrivateKeyPath();
        if (privateKeyResource != null && privateKeyResource.exists()) {
            this.privateKey = loadPrivateKey(privateKeyResource);
            log.info("Loaded RSA private key from {}", privateKeyResource);
        } else {
            log.warn("RSA private key not configured, generating ephemeral key pair for development");
            java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            this.privateKey = kpg.generateKeyPair().getPrivate();
        }
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

    private PrivateKey loadPrivateKey(Resource resource) throws Exception {
        String pem = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
        String base64 = pem.replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(base64);
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    }
}
