package com.XYai.myai.RAG.Memory.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoadSession {
    /** 历史摘要文本 */
    String summary;

    /** 会话上下文消息集合（按时间排序的消息 JSON 字符串） */
    Set<String> conversation;
}
