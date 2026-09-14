package com.XYai.myai.rag.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FileUploadEvent {
    private String taskId;            // 任务ID
    private String fileName;
    private String originalFilename;
    private String contentType;
    private byte[] fileBytes;
    private String tempFilePath;
    private Long fileSize;
    private String fileHash;
    private String collectionName;    // 集合名
    private Long userId;              // 用户ID
    private String eventId;           // 幂等ID(UUID)
    private List<Integer> upChunks;
}
