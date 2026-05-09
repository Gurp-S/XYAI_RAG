package com.XYai.myai.rag.memory.pojo;

import com.XYai.myai.rag.chat.pojo.ChatMessage;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoadSession {
    /**
     * 历史摘要文本
     */
    String summary;

    /**
     * 会话上下文消息集合（按时间排序的 ChatMessage 对象集合）
     */
    Set<ChatMessage> conversation;

    public static LoadSession fromCollection(String summary, Collection<ChatMessage> conv) {
        Set<ChatMessage> set = conv == null ? new LinkedHashSet<>() : new LinkedHashSet<>(conv);
        return LoadSession.builder().summary(summary).conversation(set).build();
    }

    public String getHistoryAsText() {
        if (conversation == null || conversation.isEmpty()) {
            return "无";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage cm : conversation) {
            // 忽略空消息
            if (cm == null) continue;
            // 拼接 User 发言
            if (cm.getUserMessage() != null && !cm.getUserMessage().isBlank()) {
                sb.append("User: ").append(cm.getUserMessage().trim()).append("\n");
            }
            // 拼接 AI 回复
            if (cm.getAssistantMessage() != null && !cm.getAssistantMessage().isBlank()) {
                sb.append("AI: ").append(cm.getAssistantMessage().trim()).append("\n");
            }
        }
        String result = sb.toString().trim();
        return result.isBlank() ? "无" : result;
    }
}
