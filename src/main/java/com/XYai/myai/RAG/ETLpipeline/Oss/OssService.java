package com.XYai.myai.RAG.ETLpipeline.Oss;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.XYai.myai.Config.OssConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.Resource;
import java.io.IOException;
import java.net.URL;
import java.util.Date;
import java.util.UUID;

/**
 * 阿里云对象存储服务实现类。
 * 提供文件的上传、删除以及访问链接生成等基础功能。
 */
@Service
public class OssService {

    /**
     * OSS 上传服务，封装对 Aliyun OSS 客户端的基本操作：上传文件、生成带签名的临时 URL、删除对象等。
     * 该服务会根据配置决定返回公有 URL 或带签名的私有访问地址。
     */

    @Resource
    private OSS ossClient;

    @Resource
    private OssConfig ossConfig; // 读取 endpoint/bucket/basePath/publicRead

    public String upload(MultipartFile file) throws IOException {
        /**
         * 将 MultipartFile 上传到 OSS，并返回可访问的 URL。
         *
         * 当配置为公共读（ossConfig.publicRead=true）时返回公开 URL；否则返回带签名的临时 URL。
         *
         * @param file 要上传的文件
         * @return 文件的访问 URL（公开或带签名的临时 URL）
         * @throws IOException 当读取文件流失败时抛出
         */
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
        /**
         * 为私有对象生成带签名的临时访问 URL。
         *
         * @param key           对象在 OSS 中的 key
         * @param expireSeconds 过期时间（秒）
         * @return 带签名的临时访问 URL 字符串，生成失败时返回 null
         */
        Date expiration = new Date(System.currentTimeMillis() + expireSeconds * 1000L);
        URL url = ossClient.generatePresignedUrl(ossConfig.getBucket(), key, expiration);
        return url == null ? null : url.toString();
    }

    public void delete(String key) {
        /**
         * 删除 OSS 中指定 key 的对象。
         *
         * @param key 对象的 key
         */
        ossClient.deleteObject(ossConfig.getBucket(), key);
    }

    /**
     * Configuration properties for the text splitter.
     */
    @Component
    @ConfigurationProperties(prefix = "splitter")
    public static class SplitterProperties {
        /** chunk size in characters (default) */
        private int chunkSize = 1200;
        /** overlap in characters */
        private int overlap = 200;
        /** when true, for Chinese prefer token-based chunking */
        private boolean chineseTokenize = true;

        public int getChunkSize() {
            return chunkSize;
        }

        public void setChunkSize(int chunkSize) {
            this.chunkSize = chunkSize;
        }

        public int getOverlap() {
            return overlap;
        }

        public void setOverlap(int overlap) {
            this.overlap = overlap;
        }

        public boolean isChineseTokenize() {
            return chineseTokenize;
        }

        public void setChineseTokenize(boolean chineseTokenize) {
            this.chineseTokenize = chineseTokenize;
        }
    }
}
