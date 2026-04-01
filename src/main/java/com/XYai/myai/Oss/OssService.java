package com.XYai.myai.Oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.XYai.myai.Config.OssConfig;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.UUID;

@Service
public class OssService {

    @Resource
    private OSS ossClient;

    @Resource
    private OssConfig ossConfig; // 读取 endpoint/bucket/basePath/publicRead

    public String upload(MultipartFile file) throws IOException {
        String original = file.getOriginalFilename();
        String ext = "";
        if (original != null && original.contains(".")) {
            ext = original.substring(original.lastIndexOf("."));
        }
        String keyPrefix = (ossConfig.getBasePath() != null ? ossConfig.getBasePath().replaceAll("/+$", "") + "/" : "");
        String key = keyPrefix + UUID.randomUUID().toString().replace("-", "") + ext;

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        if (file.getContentType() != null) {
            metadata.setContentType(file.getContentType());
        }

        ossClient.putObject(ossConfig.getBucket(), key, file.getInputStream(), metadata);

        if (Boolean.TRUE.equals(ossConfig.getPublicRead())) {
            // 公共读：直接拼 URL
            String url = "https://" + ossConfig.getBucket() + "." + ossConfig.getEndpoint() + "/" + key;
            return url;
        } else {
            // 私有：生成带签名的临时 URL（默认 1 hour）
            return generatePresignedUrl(key, 3600);
        }
    }

    public String generatePresignedUrl(String key, int expireSeconds) {
        Date expiration = new Date(System.currentTimeMillis() + expireSeconds * 1000L);
        URL url = ossClient.generatePresignedUrl(ossConfig.getBucket(), key, expiration);
        return url == null ? null : url.toString();
    }

    public void delete(String key) {
        ossClient.deleteObject(ossConfig.getBucket(), key);
    }
}
