package com.XYai.myai.rag.channel.POJO;

import com.XYai.myai.rag.intent.POJO.NodesScore;
import com.XYai.myai.rag.intent.POJO.SubQuestionIntent;
import lombok.AllArgsConstructor;
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

    /** 原始或重写后的查询文本（用于向量检索/LLM） */
    private String question;

    /** 来自意图识别的 KB 意图候选 （每个 SubQuestionIntent 包含多个 IntentNode） */
    @Builder.Default
    private List<SubQuestionIntent> kbIntents = new ArrayList<>();

    /** 来自意图识别或融合后的节点置信度列表（用于判断是否需全局检索） */
    @Builder.Default
    private NodesScore intents = new NodesScore();

    /** 额外元数据（例如 sessionId/userId/请求标记等） */
    private Map<String, String> metadata;

    /** 每个通道默认的 topK，通道可以根据需要覆盖 */
    private Integer topK;

}


