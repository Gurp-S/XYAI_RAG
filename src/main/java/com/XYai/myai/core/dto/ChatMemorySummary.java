package com.XYai.myai.core.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_memory_summary")
public class ChatMemorySummary {

    @TableId(value = "conversation_id", type = IdType.INPUT)
    private String conversationId;

    @TableField("summary_text")
    private String summaryText;
}
