package com.XYai.myai.RAG.ETLpipeline.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.document.Document;


import java.util.List;
import java.util.ArrayList;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpLoadAccumulator {
    /**
     * 所有解析并分块后的 Document 列表，准备加入向量数据库或后续处理。
     */
    private List<Document> allChunks = new ArrayList<>();

    /**
     * 上传到 OSS 后返回的 URL 列表，用于记录已成功上传的文件地址。
     */
    private List<String> uploadedUrls = new ArrayList<>();

    /**
     * 处理失败的文件名列表（或标识），用于返回给前端或日志记录。
     */
    private List<String> failedFiles = new ArrayList<>();
}
