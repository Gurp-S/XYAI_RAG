package com.XYai.myai.security;

import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Loads RSA keys from PEM content (env/secret store) or a Spring {@link Resource} path.
 * Private keys must not be packaged into the application JAR.
 */
public final class RsaKeyLoader {

    private RsaKeyLoader() {
    }

    public static PrivateKey loadPrivateKey(String pemContent, Resource path) throws Exception {
        String pem = resolvePem(pemContent, path, "RSA private key");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decodePem(pem)));
    }

    public static PublicKey loadPublicKey(String pemContent, Resource path) throws Exception {
        String pem = resolvePem(pemContent, path, "RSA public key");
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decodePem(pem)));
    }

    private static String resolvePem(String pemContent, Resource path, String label) throws Exception {
        if (StringUtils.hasText(pemContent)) {
            return pemContent;
        }
        if (path != null && path.exists()) {
            return StreamUtils.copyToString(path.getInputStream(), StandardCharsets.UTF_8);
        }
        throw new IllegalStateException(label + " is not configured. "
                + "Set JWT_RSA_PRIVATE_KEY / JWT_RSA_PUBLIC_KEY (PEM) or "
                + "JWT_RSA_PRIVATE_KEY_PATH / JWT_RSA_PUBLIC_KEY_PATH (file). "
                + "See config/keys/README.md");
    }

    static byte[] decodePem(String pem) {
        String base64 = pem
                .replaceAll("-----BEGIN (.*)-----", "")
                .replaceAll("-----END (.*)-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(base64);
    }
}
