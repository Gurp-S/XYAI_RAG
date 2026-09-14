package com.XYai.myai.rag.ragPojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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

    /** 用户长期记忆（跨会话事实，个性化背景；无则为 null/空） */
    private String longTermMemoryText;

    /** 原始用户消息 */
    private String originalMessage;

    /** 检索质量门控判定：true 表示重检索后证据仍不足，Prompt 将附加拒答提示 */
    @Builder.Default
    private boolean insufficientEvidence = false;

    /** 引用来源列表（与 retrieveText 中的 [n] 编号一一对应，供前端/审计使用） */
    @Builder.Default
    private List<String> citations = List.of();
}