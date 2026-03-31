package com.XYai.myai.RAG.retriever;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 检索器接口，定义单路检索能力。
 */
@FunctionalInterface
public interface Retriever {

    /**
     * 执行检索并返回命中结果。
     *
     * @param query 查询文本
     * @param topK 期望返回的最大结果数
     * @param hints 检索提示参数（如意图、过滤条件）
     * @return 异步检索结果
     */
    CompletableFuture<RetrievalResult> retrieve(String query, int topK, Map<String, Object> hints);
}

