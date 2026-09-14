package com.XYai.myai.rag.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class MilvusEvent {
    private String eventId;           // 幂等ID (UUID)
    private String commandType;       // 见下面对照表
    private String fileChunkId;          // chunkId (1-based)
    private List<Integer> chunkIds;   // 分块序号列表（批量用）
}
