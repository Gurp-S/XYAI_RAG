package com.XYai.myai.rag.etlpipeline.kafka;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChunkResult {
    private String taskId;            // 任务ID
    private byte[] fileBytes;         // 文件内容
    private String fileName;          // 文件名
    private String collectionName;    // 集合名
    private Long userId;              // 用户ID
    private String eventId;           // 幂等ID(UUID)
}
