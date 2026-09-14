package com.XYai.myai.security.pojo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    private String issuer = "XYai";
    private long accessTokenExpSec = 900;
    private long refreshTokenExpSec = 1209600;
    private String refreshCookieName = "refresh_token";

    /**
     * PEM content (preferred in production via env/secret store).
     * Bound from {@code JWT_RSA_PRIVATE_KEY} / {@code jwt.rsa-private-key}.
     */
    private String rsaPrivateKey;

    /**
     * PEM content (preferred in production via env/secret store).
     * Bound from {@code JWT_RSA_PUBLIC_KEY} / {@code jwt.rsa-public-key}.
     */
    private String rsaPublicKey;

    /** Fallback file/classpath resource when PEM content is empty. */
    private Resource rsaPrivateKeyPath;

    /** Fallback file/classpath resource when PEM content is empty. */
    private Resource rsaPublicKeyPath;

    private boolean httpSecure = true;
}
