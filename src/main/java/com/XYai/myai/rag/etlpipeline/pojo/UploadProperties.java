package com.XYai.myai.rag.etlpipeline.pojo;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = "upload")
public class UploadProperties {
    /**
     * 是否启用upload
     */
    Boolean upLoadEnabled;

    /**
     * 是否启用 RAG（将文档分块后存入向量数据库）
     */
    Boolean ragEnabled;

    /**
     * 是否启用将文件上传到 OSS
     */
    Boolean ossEnabled;
}