package com.XYai.myai.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 密钥对在测试运行时生成，仓库内不存放任何 PEM 私钥
 * （既不把私钥提交进版本库，也避免被 GitHub push protection 拦截）。
 */
class RsaKeyLoaderTest {

    private static final KeyPair KEY_PAIR = generateKeyPair();

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成测试用 RSA 密钥对", e);
        }
    }

    private static String toPem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }

    /** 以 PEM 字符串注入，对应 JWT_RSA_PRIVATE_KEY / JWT_RSA_PUBLIC_KEY 用法。 */
    @Test
    void loadsKeysFromPemContent() throws Exception {
        PrivateKey privateKey = RsaKeyLoader.loadPrivateKey(
                toPem("PRIVATE KEY", KEY_PAIR.getPrivate().getEncoded()), null);
        PublicKey publicKey = RsaKeyLoader.loadPublicKey(
                toPem("PUBLIC KEY", KEY_PAIR.getPublic().getEncoded()), null);

        assertNotNull(privateKey);
        assertNotNull(publicKey);
    }

    /** 以文件 Resource 注入，对应 JWT_RSA_PRIVATE_KEY_PATH / JWT_RSA_PUBLIC_KEY_PATH 用法。 */
    @Test
    void loadsKeysFromFileResource(@TempDir Path tempDir) throws Exception {
        Path privateKeyPath = tempDir.resolve("private_pkcs8.pem");
        Path publicKeyPath = tempDir.resolve("public_x509.pem");
        Files.writeString(privateKeyPath,
                toPem("PRIVATE KEY", KEY_PAIR.getPrivate().getEncoded()), StandardCharsets.UTF_8);
        Files.writeString(publicKeyPath,
                toPem("PUBLIC KEY", KEY_PAIR.getPublic().getEncoded()), StandardCharsets.UTF_8);

        assertNotNull(RsaKeyLoader.loadPrivateKey(null, new FileSystemResource(privateKeyPath)));
        assertNotNull(RsaKeyLoader.loadPublicKey(null, new FileSystemResource(publicKeyPath)));
    }

    @Test
    void failsClearlyWhenMissing() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> RsaKeyLoader.loadPrivateKey("", null));
        assertTrue(ex.getMessage().contains("not configured"));
    }
}
