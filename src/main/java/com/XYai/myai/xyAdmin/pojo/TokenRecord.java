package com.XYai.myai.xyAdmin.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Token 用量记录实体
 * 记录每次模型调用的 token 消耗
 * 主键为 chatMessageId（消息ID）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_message_id")
public class TokenRecord {

    /** 消息ID（主键） */
    @TableId(value = "chat_message_id", type = IdType.INPUT)
    private Long chatMessageId;

    /** 会话ID */
    @TableField("conversation_id")
    private Long conversationId;

    /** 用户ID */
    @TableField("user_id")
    private Long userId;

    /** 使用的模型名称 */
    @TableField("model_name")
    private String modelName;

    /** prompt tokens */
    @TableField("prompt_tokens")
    private Integer promptTokens;

    /** completion tokens */
    @TableField("completion_tokens")
    private Integer completionTokens;

    /** total tokens */
    @TableField("total_tokens")
    private Integer totalTokens;

    /** 耗时（毫秒） */
    @TableField("cost_ms")
    private Long costMs;

    /** 调用类型：chat / mcp / fast / enhance */
    @TableField("call_type")
    private String callType;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
