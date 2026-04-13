package com.XYai.myai.RAG.Chat.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天消息实体类。
 * 用于封装用户提交的消息请求以及后端返回的回复信息，包含用户 ID 以便关联用户上下回文。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    /** 用户的原始输入消息 */
    String userMessage;

    /** 模型/助手的回复内容 */
    String assistantMessage;

    /** 用户的唯一标识ID */
    Long userId;
}