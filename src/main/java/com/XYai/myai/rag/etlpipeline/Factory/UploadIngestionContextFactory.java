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
     * @return 封装完成的 IngestionContext 上下文对象
     * @throws IOException 文件读取IO异常
     */
    public IngestionContext create(MultipartFile file, String collectionName, User user) throws IOException {
        String fileName = safeFileName(file);
        String fileId = IdUtil.getSnowflakeNextIdStr();
        String kbId = IdUtil.getSnowflakeNextIdStr();

        // ========== 传入用户信息，用于权限注入 ==========
        return createContext(
                fileId,
                fileName,
                "upload",
                file.getBytes(),
                file.getContentType(),
                collectionName,
                kbId,
                fileName,
                file.getSize(),
                user  // 传入用户
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
        return createContext("1", sourceUri, sourceType, null, null, collectionName, kbId, null, null, user);
    }

    /**
     * inline 文本入口：直接把文本转成字节并放入 rawBytes，后续可直接走 Parser。
     */
    public IngestionContext createInline(String content, String collectionName, String kbId, User user) {
        byte[] rawBytes = content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8);
        return createContext("1", "inline", "inline", rawBytes, "text/plain", collectionName, kbId, "inline", (long) rawBytes.length, user);
    }

    /**
     * 核心：创建上下文（已加入权限）
     */
    private IngestionContext createContext(String fileId,
                                           String sourceUri,
                                           String sourceType,
                                           byte[] rawBytes,
                                           String mimeType,
                                           String collectionName,
                                           String kbId,
                                           String fileName,
                                           Long fileSize,
                                           User user) { // 这里加入 User
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(IngestionContext.META_FILE_ID, fileId);
        metadata.put(IngestionContext.META_SOURCE_URI, sourceUri);
        metadata.put(IngestionContext.META_SOURCE_TYPE, sourceType);
        metadata.put(IngestionContext.META_RAW_BYTES, rawBytes);
        metadata.put(IngestionContext.META_MIME_TYPE, mimeType);
        metadata.put(IngestionContext.META_COLLECTION_NAME, collectionName);
        metadata.put(IngestionContext.META_KB_ID, kbId);
        metadata.put(IngestionContext.META_FILE_NAME, fileName == null ? sourceUri : fileName);
        metadata.put(IngestionContext.META_FILE_SIZE, fileSize);
        metadata.put(IngestionContext.META_FILE_CONTENT_TYPE, mimeType);
        metadata.put(IngestionContext.META_TIME_CREATE,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );

        // ====================== 【自动注入权限字段】 ======================
        if (user != null) {
            // 上传者ID（Long）
            metadata.put(IngestionContext.META_OWNER_ID, user.getId());

            // 组ID（Long）
            metadata.put(IngestionContext.META_GROUP_ID, user.getGroupId());

            // 默认私有
            metadata.put(IngestionContext.META_VISIBILITY, "private");

            // 默认空共享列表（JSON array）
            metadata.put(IngestionContext.META_SHARED_WITH, List.of());
        } else {
            // 无用户时的安全默认值
            metadata.put(IngestionContext.META_OWNER_ID, 0L);
            metadata.put(IngestionContext.META_GROUP_ID, 0L);
            metadata.put(IngestionContext.META_VISIBILITY, "private");
            metadata.put(IngestionContext.META_SHARED_WITH, List.of());
        }

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