package com.XYai.myai.rag.channel.pojo;

import com.XYai.myai.rag.intent.pojo.NodesScore;
import com.XYai.myai.rag.intent.pojo.SubQuestionIntent;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchContext {

    /**
     * 原始问题
     */
    private String originalQuery;


    /**
     * 查询文本（原始或重写后）。
     * 所有检索通道与 Rerank 阶段都会读取该字段。
     */
    private RewriteResult rewriteQuestion;

    /**
     * 对话ID
     * 对话的唯一标识
     */
    private String conversationId;

    /**
     * 意图候选列表。
     * 通道启停（isEnabled）常基于该字段判断是否做定向检索或全局兜底检索。
     */
    @Builder.Default
    private List<SubQuestionIntent> kbIntents = new ArrayList<>();

    /**
     * 节点置信度信息。
     * 可用于动态调节召回策略（例如低置信度时扩大召回范围）。
     */
    @Builder.Default
    private NodesScore intents = new NodesScore();

    /**
     * 扩展元数据。
     * 建议放 sessionId/userId/role/groupId/requestId 等，供过滤与审计使用。
     */
    private Map<String, String> metadata;

    /**
     * 默认 topK。
     * 通道可按自身策略覆盖（例如向量通道 topK=10，全文通道 topK=20）。
     */
    private Integer topK;

}