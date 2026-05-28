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
@TableName("chat_memory_interaction")
public class ChatSessionRecord {

    @TableId(value = "conversation_id", type = IdType.INPUT)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long conversationId;

    @TableField("user_id")
    private Long userId;

    @TableField("title")
    private String title;

    @TableField("summary_text")
    private String summaryText;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
