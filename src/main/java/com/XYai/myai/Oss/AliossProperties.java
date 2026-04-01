package com.XYai.myai.Oss;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds properties under `alioss.*` (e.g. alioss.access-key-id) so IDE and Spring can resolve them.
 */
@Component
@ConfigurationProperties(prefix = "alioss")
@Getter
@Setter
public class AliossProperties {
    private String endpoint;
    private String accessKeyId;
    private String accessKeySecret;
    private String bucketName;
}

