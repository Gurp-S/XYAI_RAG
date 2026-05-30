package com.XYai.myai.rag.etlpipeline.kafka;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkEvent {
    private String taskId;            // 所属任务ID
    private String chunkId;           // chunk唯一ID
    private String chunkText;         // chunk文本
    private String collectionName;    // 集合名
    private Long userId;              // 用户ID
    private String fileId;            // 文件ID（chunk的fileId部分）
    private Integer chunkIndex;       // chunk序号（从1开始）
    private Integer totalChunks;      // 该文件总chunk数
    private String eventId;           // 幂等ID(UUID)
}