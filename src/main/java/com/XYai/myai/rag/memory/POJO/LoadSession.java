package com.XYai.myai.rag.memory.POJO;

import com.XYai.myai.rag.chat.POJO.ChatMessage;
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
}
