package com.XYai.myai.core.dto;

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
@TableName("chat_memory_interaction")
public class ChatSessionRecord {

    @TableId(value = "conversation_id", type = IdType.INPUT)
    private String conversationId;

    @TableField("user_message")
    private String userMessage;

    @TableField("assistant_message")
    private String assistantMessage;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
