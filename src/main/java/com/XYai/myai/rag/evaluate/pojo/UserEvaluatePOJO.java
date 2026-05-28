package com.XYai.myai.rag.evaluate.pojo;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
@TableName("xy_user_evaluate")
public class UserEvaluatePOJO {
    @TableId
    private Long messageId;   // 主键，对应数据库 message_id

    private Long conversationId;

    private Long userId;

    @TableField("`feedback`")    // 避免 MySQL 关键字冲突
    private Integer feedback;    // 1-点赞，0-点踩

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}