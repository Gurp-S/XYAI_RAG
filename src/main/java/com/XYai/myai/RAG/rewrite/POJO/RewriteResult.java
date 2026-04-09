package com.XYai.myai.RAG.rewrite.POJO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RewriteResult {
    /** 重写后的查询文本（更适合检索或模型输入） */
    String rewrittenQuery; //重写后的标准句子

    /** 被拆分出的子问题列表（用于多轮或分步检索） */
    List<String> subQuery; //拆分的子问题
}