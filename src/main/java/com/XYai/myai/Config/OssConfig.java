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
    /**
     * 阿里云 OSS 配置与客户端 Bean。
     * 配置项通过前缀 aliyun.oss.* 注入（endpoint、accessKeyId、accessKeySecret、bucket、basePath、publicRead）。
     */
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
        /* 创建并返回一个 OSS 客户端实例，Bean 在容器销毁时会调用 shutdown 方法。 */
        if (ossClient == null) {
            ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
        }
        return ossClient;
    }
}
