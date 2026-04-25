package com.XYai.myai.rag.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SummaryMessage {
    /**
     * 现有摘要文本（用于与新消息合并去重）
     */
    String lastestSummary;

    /**
     * 待摘要的聊天消息内容（可能为 JSON 字符串）
     */
    String chatMessage;
}
