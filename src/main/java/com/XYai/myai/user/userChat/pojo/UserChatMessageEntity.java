package com.XYai.myai.user.userChat.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_chat_message")
public class UserChatMessageEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("conversation_id")
    private String conversationId;

    @TableField("target_type")
    private String targetType;

    @TableField("target_id")
    private String targetId;

    @TableField("sender_id")
    private String senderId;

    @TableField("sender_name")
    private String senderName;

    @TableField("content")
    private String content;

    @TableField("created_at")
    private Long createdAt;

    @TableField("status")
    private String status;
}
