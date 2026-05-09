package com.XYai.myai.rag.rewrite.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 查询重写结果对象。
 * 用于描述 LLM 或规则引擎返回的重写后查询及其拆分子问题。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RewriteResult {
    /**
     * 重写后的查询文本（更适合检索或模型输入）
     */
    String rewrittenQuery; // 重写后的标准句子

    /**
     * 被拆分出的子问题列表（用于多轮或分步检索）
     */
    List<String> subQuery; // 拆分的子问题
}