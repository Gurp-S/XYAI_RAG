package com.XYai.myai.rag.milvus.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

/**
 * 数据库存储：文件主记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileRecord {
    private Long fileId;
    private String fileName;
    private String kbId;          // 知识库ID
    private String ownerId;
    private String groupId;
    private String visibility;
    private LocalDateTime createTime;
}