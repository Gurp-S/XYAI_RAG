package com.XYai.myai.Memory;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_conversation")
public class ChatConversation {

    @TableId(value = "chat_message_id", type = IdType.INPUT) // 或 IdType.ASSIGN_UUID / ASSIGN_ID / AUTO
    private String chatMessageId;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("user_message")
    private String userMessage;

    @TableField("assistant_message")
    private String assistantMessage;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
