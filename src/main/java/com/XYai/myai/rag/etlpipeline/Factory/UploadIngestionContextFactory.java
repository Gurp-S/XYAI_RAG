package com.XYai.myai.rag.etlpipeline.Factory;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.rag.etlpipeline.POJO.IngestionContext;
import com.XYai.myai.user.POJO.User;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
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
     * @param user           当前登录用户
     * @param fileHashId     fileId
     * @param copyChunks
     * @return 封装完成的 IngestionContext 上下文对象
     * @throws IOException 文件读取IO异常
     */
    public IngestionContext create(MultipartFile file, String collectionName, User user, String fileHashId, List<Long> copyChunks)
            throws IOException {
        String fileName = safeFileName(file);
        String kbId = IdUtil.getSnowflakeNextIdStr();
        // ========== 传入用户信息，用于权限注入 ==========
        return createContext(
                fileHashId,
                fileName,
                "upload",
                file.getBytes(),
                file.getContentType(),
                collectionName,
                kbId,
                fileName,
                file.getSize(),
                copyChunks,
                user // 传入用户
        );
    }

    /**
     * 外部来源入口：URL、本地文件路径都可以统一走这里。
     */
    public IngestionContext createFromSource(String sourceUri,
                                             String sourceType,
                                             String collectionName,
                                             String kbId,
                                             User user) { // 加 user
        return createContext("1", sourceUri, sourceType, null, null, collectionName, kbId, null, null, List.of(), user);
    }

    /**
     * inline 文本入口：直接把文本转成字节并放入 rawBytes，后续可直接走 Parser。
     */
    public IngestionContext createInline(String content, String collectionName, String kbId, User user) {
        byte[] rawBytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        return createContext("1", "inline", "inline", rawBytes, "text/plain", collectionName, kbId, "inline",
                (long) rawBytes.length, List.of(), user);
    }

    /**
     * 核心：创建上下文（已加入权限）
     */
    private IngestionContext createContext(String fileHashId,
                                           String sourceUri,
                                           String sourceType,
                                           byte[] rawBytes,
                                           String mimeType,
                                           String collectionName,
                                           String kbId,
                                           String fileName,
                                           Long fileSize,
                                           List<Long> copyChunks,
                                           User user) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(IngestionContext.META_FILE_ID, fileHashId);
        metadata.put(IngestionContext.META_SOURCE_URI, sourceUri);
        metadata.put(IngestionContext.META_SOURCE_TYPE, sourceType);
        metadata.put(IngestionContext.META_RAW_BYTES, rawBytes);
        metadata.put(IngestionContext.META_MIME_TYPE, mimeType);
        metadata.put(IngestionContext.META_COLLECTION_NAME, collectionName);
        metadata.put(IngestionContext.META_KB_ID, kbId);
        metadata.put(IngestionContext.META_FILE_NAME, fileName == null ? sourceUri : fileName);
        metadata.put(IngestionContext.META_FILE_SIZE, fileSize);
        metadata.put(IngestionContext.META_FILE_CONTENT_TYPE, mimeType);
        metadata.put(IngestionContext.META_COPY_CHUNK, copyChunks);
        metadata.put(IngestionContext.META_TIME_CREATE,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

        // ====================== 【自动注入权限字段】 ======================
            // 默认私有
            metadata.put(IngestionContext.META_VISIBILITY, "private");
        // 构建文档
        Document document = Document.builder()
                .text("")
                .metadata(metadata)
                .build();

        return IngestionContext.builder()
                .document(document)
                .build();
    }

    /**
     * 安全获取文件名
     */
    private String safeFileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        return (original == null || original.isBlank()) ? "unknown" : original;
    }
}