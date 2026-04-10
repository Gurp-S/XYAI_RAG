package com.XYai.myai.RAG.ETLpipeline.POJO;

import lombok.*;
import org.springframework.ai.document.Document;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IngestionContext implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /*
        Document
                text
                metadata:
                    sourceUri
                    sourceType
                    rawBytes
                    mimeType
                    enhancedText
                    kbId
                    collectionName
                    fileName
                    fileSize
                    fileContentType
                    chunkSize
        */
    public static final String META_SOURCE_URI = "sourceUri";
    public static final String META_SOURCE_TYPE = "sourceType";
    public static final String META_RAW_BYTES = "rawBytes";
    public static final String META_MIME_TYPE = "mimeType";
    public static final String META_ENHANCED_TEXT = "enhancedText";
    public static final String META_COLLECTION_NAME = "collectionName";
    public static final String META_KB_ID = "kbId";
    public static final String META_FILE_NAME = "fileName";
    public static final String META_FILE_SIZE = "fileSize";
    public static final String META_FILE_CONTENT_TYPE = "fileContentType";
    public static final String META_CHUNK_SIZE = "chunkSize";

    // 主文档：文本在 text，其他信息在 metadata
    @Builder.Default
    private Document document = Document.builder().text("").metadata(new HashMap<>()).build();

    /**
     * 用 @Builder.Default 保证当使用 no-args constructor 时也有非空的默认集合；
     * 用 @Singular 在 builder 上提供单个元素的添加方法（chunk(...) / log(...)）
     */
    @Builder.Default
    private List<Document> chunks = new ArrayList<>();
    // ChunkerNode分块后的文档块

    @Builder.Default
    private List<NodeLog> logs = new ArrayList<>();

}