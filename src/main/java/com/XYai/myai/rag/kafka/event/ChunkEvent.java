package com.XYai.myai.rag.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/** Parser + Chunker 处理完后，每个 chunk 发这个消息 */
@Data @NoArgsConstructor @AllArgsConstructor
public class ChunkEvent {
    private String eventId;
    private String taskId;
    /** Document ID (fileHash:chunkId) */
    private String chunkId;
    private Integer chunkSize;
    /** Document 文本内容 */
    private String chunkText;
    /** Document 元数据 */
    private Map<String, Object> chunkMetadata;
    private String collectionName;
    private Long userId;
    private String fileName;
}
