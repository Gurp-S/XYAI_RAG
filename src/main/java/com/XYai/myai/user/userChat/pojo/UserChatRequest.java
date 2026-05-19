package com.XYai.myai.user.userChat.pojo;

/**
 * 用户/群聊请求体。
 */
public record UserChatRequest(
        String message,
        String conversationId,
        Long userId,
        String targetType,
        String targetId,
        String targetName,
        String senderName,
        String status
) {
}