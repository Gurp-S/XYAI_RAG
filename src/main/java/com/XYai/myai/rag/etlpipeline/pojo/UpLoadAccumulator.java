package com.XYai.myai.rag.etlpipeline.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpLoadAccumulator {

    /**
     * 所有解析并分块后的 Document 列表
     */
    @Builder.Default
    private List<Document> allChunks = new ArrayList<>();

    /**
     * 上传到 OSS 后返回的 URL 列表
     */
    @Builder.Default
    private List<String> uploadedUrls = new ArrayList<>();

    /**
     * 处理失败的文件名列表
     */
    @Builder.Default
    private List<String> failedFiles = new ArrayList<>();

    /**
     * 本次请求的任务ID（traceId）
     */
    private String taskId;

    /**
     * OSS 上传失败的文件
     */
    @Builder.Default
    private List<String> ossFailedFiles = new ArrayList<>();
}