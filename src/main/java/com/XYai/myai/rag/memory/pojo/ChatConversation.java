package com.XYai.myai.rag.memory.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_conversation")
public class ChatConversation {

    @TableId(value = "chat_message_id", type = IdType.ASSIGN_ID)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long chatMessageId;

    @TableField("user_id")
    private Long userId;

    @TableField("conversation_id")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long conversationId;

    @TableField("user_message")
    private String userMessage;

    @TableField("assistant_message")
    private String assistantMessage;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("`feedback`") // 避免 MySQL 关键字冲突
    private Integer feedback; // 1-点赞，0-点踩

    /** Token 追踪字段 */
    @TableField("prompt_tokens")
    private Integer promptTokens;

    @TableField("completion_tokens")
    private Integer completionTokens;

    @TableField("total_tokens")
    private Integer totalTokens;

    @TableField("model_name")
    private String modelName;

    @TableField("cost_ms")
    private Long costMs;
}
