package com.XYai.myai.Config;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


@Configuration
@ConfigurationProperties(prefix = "aliyun.oss")
public class OssConfig {
    // standard getters & setters generated...
    @Setter
    @Getter
    private String endpoint;
    @Setter
    @Getter
    private String accessKeyId;
    @Setter
    @Getter
    private String accessKeySecret;
    @Setter
    @Getter
    private String bucket;
    @Setter
    @Getter
    private String basePath;
    @Setter
    @Getter
    private Boolean publicRead;

    private OSS ossClient;

    @Bean(destroyMethod = "shutdown")
    public OSS ossClient() {
        if (ossClient == null) {
            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        }
        return ossClient;
    }
}
