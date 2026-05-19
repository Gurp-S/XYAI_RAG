package com.XYai.myai.user.userChat.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 内部使用的聊天消息实体类（用于内存队列存储）。
 * 字段与持久化对象保持简单一致，适合轻量消息存储与回放。
 */
@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    long id;
    String conversationId;
    String targetType;
    String targetId;
    String senderId;
    String senderName;
    String content;
    long timestamp;
    String status;
}
