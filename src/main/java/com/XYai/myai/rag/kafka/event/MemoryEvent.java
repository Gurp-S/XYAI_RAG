package com.XYai.myai.rag.kafka.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data @NoArgsConstructor @AllArgsConstructor
public class MemoryEvent {
    private String eventId;
    private String commandType;  // SAVE_MESSAGE, COMPRESS, GENERATE_SUMMARY
    private Long conversationId;
    private Long userId;
    private String messageJson;  // JSON 序列化的消息数据
}