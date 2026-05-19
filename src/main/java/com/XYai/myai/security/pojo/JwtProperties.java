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

    private Resource rsaPrivateKeyPath;
    private Resource rsaPublicKeyPath;

    private boolean httpSecure = true;
}
