package com.XYai.myai.rag.etlpipeline.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 上传相关配置，前缀 upload
 */
@Data
@Component
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UploadProperties {

    /** 是否启用上传功能 */
    @Builder.Default
    private Boolean upLoadEnabled = true;

    /** 是否启用RAG（文档分块入库） */
    @Builder.Default
    private Boolean ragEnabled = true;

    /** 是否启用OSS文件上传 */
    @Builder.Default
    private Boolean ossEnabled = false;

    /** 文件内存缓存阈值（超过则写临时文件），默认10MB */
    @Builder.Default
    private long maxInMemoryFileBytes = 10L * 1024L * 1024L;

    /** 异常消息最大长度（截断后存入任务状态） */
    @Builder.Default
    private int maxErrorMessageLength = 200;
}