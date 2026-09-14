// AclCommandEvent.java
package com.XYai.myai.rag.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data @NoArgsConstructor @AllArgsConstructor
public class AclCommandEvent {
    private String eventId;           // 幂等ID (UUID)
    private String commandType;       // 见下面对照表
    private String collectionName;    // 集合名
    private String fileId;            // 文件哈希ID
    private Integer chunkId;          // 分块序号 (1-based)
    private List<Integer> chunkIds;   // 分块序号列表（批量用）
    private Long userId;              // 当前操作用户
    private Long targetUserId;        // 分享目标用户
    private Integer chunkSize;        // 文件总分块数
}