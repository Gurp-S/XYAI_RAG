package com.XYai.myai.RAG.ETLpipeline.Factory;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.RAG.ETLpipeline.POJO.IngestionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 上传文件 摄取上下文工厂
 * 作用：将前端上传的文件封装成 RAG ETL 流程需要的 IngestionContext 对象
 * 用于后续文档解析、分块、向量化、入库等流程
 */
@Component
@RequiredArgsConstructor
public class UploadIngestionContextFactory {

    /**
     * 创建文件摄取上下文
     *
     * @param file           前端上传的文件对象
     * @param collectionName 向量数据库集合名（用于区分不同知识库存储）
     * @return 封装完成的 IngestionContext 上下文对象
     * @throws IOException 文件读取IO异常
     */
    public IngestionContext create(MultipartFile file, String collectionName) throws IOException {
        // 获取安全文件名（防止文件名为空）
        String fileName = safeFileName(file);
        String fileId = IdUtil.getSnowflakeNextIdStr();
        String kbId = IdUtil.getSnowflakeNextIdStr();
        return createContext(
                fileId,
                fileName,
                "upload",
                file.getBytes(),
                file.getContentType(),
                collectionName,
                kbId,
                fileName,
                file.getSize()
        );
    }

    /**
     * 外部来源入口：URL、本地文件路径都可以统一走这里。
     */
    public IngestionContext createFromSource(String sourceUri,
                                             String sourceType,
                                             String collectionName,
                                             String kbId) {
        return createContext("1",sourceUri, sourceType, null, null, collectionName, kbId, null, null);
    }

    /**
     * inline 文本入口：直接把文本转成字节并放入 rawBytes，后续可直接走 Parser。
     */
    public IngestionContext createInline(String content, String collectionName, String kbId) {
        byte[] rawBytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        return createContext("1","inline", "inline", rawBytes, "text/plain", collectionName, kbId, "inline", (long) rawBytes.length);
    }

    private IngestionContext createContext(String fileId,
                                           String sourceUri,
                                           String sourceType,
                                           byte[] rawBytes,
                                           String mimeType,
                                           String collectionName,
                                           String kbId,
                                           String fileName,
                                           Long fileSize) {
        // 构建文档元数据（文件信息 + 业务信息）
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(IngestionContext.META_FILE_ID, fileId);  //文件ID雪花算法递增
        metadata.put(IngestionContext.META_SOURCE_URI, sourceUri);                // 文件来源URI
        metadata.put(IngestionContext.META_SOURCE_TYPE, sourceType);              // 数据来源类型：上传/外部/inline
        metadata.put(IngestionContext.META_RAW_BYTES, rawBytes);                  // 文件原始字节流
        metadata.put(IngestionContext.META_MIME_TYPE, mimeType);                  // 文件MIME类型
        metadata.put(IngestionContext.META_COLLECTION_NAME, collectionName);      // 向量数据库collectionName
        metadata.put(IngestionContext.META_KB_ID, kbId); //知识库编号
        metadata.put(IngestionContext.META_FILE_NAME, fileName == null ? sourceUri : fileName); // 文件名
        metadata.put(IngestionContext.META_FILE_SIZE, fileSize);                   // 文件大小
        metadata.put(IngestionContext.META_FILE_CONTENT_TYPE, mimeType);          // 文件内容类型
        metadata.put(IngestionContext.META_TIME_CREATE,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );  //文件创建时间

        // 构建 Spring AI 标准 Document
        Document document = Document.builder()
                .text("")
                .metadata(metadata)
                .build();

        // 封装返回
        return IngestionContext.builder()
                .document(document)
                .build();
    }

    /**
     * 安全获取文件名
     * 处理：文件名为null 或 空字符串时，返回默认值 unknown
     *
     * @param file 上传文件
     * @return 安全的文件名
     */
    private String safeFileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        return (original == null || original.isBlank()) ? "unknown" : original;
    }
}