package com.XYai.myai.core.retriever;

import com.XYai.myai.core.dto.RetrievalResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 多路检索协调器接口。
 */
@FunctionalInterface
public interface RetrieverCoordinator {

    /**
     * 并行协调多个检索器并聚合结果。
     *
     * @param query 查询文本
     * @param topK 聚合后保留的最大结果数
     * @param retrievers 参与检索的检索器列表
     * @return 异步聚合检索结果
     */
    CompletableFuture<RetrievalResult> retrieveMulti(String query, int topK, List<Retriever> retrievers);
}

