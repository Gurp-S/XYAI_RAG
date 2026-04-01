package com.XYai.myai.Chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessage {
    /** 用户的原始输入消息 */
    String userMessage;

    /** 模型/助手的回复内容 */
    String assistantMessage;
}