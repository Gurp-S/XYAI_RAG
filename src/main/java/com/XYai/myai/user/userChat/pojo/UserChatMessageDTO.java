package com.XYai.myai.user.userChat.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户聊天消息的数据传输对象（DTO）。
 * 包含消息的基本元信息，用于前后端交互与序列化。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserChatMessageDTO {
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
