package com.XYai.myai.Controller;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@NoArgsConstructor
@Component
@ConfigurationProperties(prefix = "upload")
public class UploadProperties {
    /** 是否启用 RAG（将文档分块后存入向量数据库） */
    Boolean ragEnabled;    

    /** 是否启用将文件上传到 OSS */
    Boolean ossEnabled;    

    /** 是否启用使用 spring-ai 的 TextSplitter 进行分块（否则使用本地分块实现） */
    Boolean splitWithSpringAIEnabled;
}