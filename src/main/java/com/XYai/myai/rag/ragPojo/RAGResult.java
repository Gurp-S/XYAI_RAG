package com.XYai.myai.rag.ragPojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RAG处理结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RAGResult {

    /** 检索到的文档内容 */
    private String retrieveText;

    /** MCP工具调用结果 */
    private String mcpText;

    /** 历史对话摘要 */
    private String summaryText;

    /** 历史对话内容 */
    private String historyText;

    /** 原始用户消息 */
    private String originalMessage;
}