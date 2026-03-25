package com.XYai.myai.core.rag;

import com.XYai.myai.core.dto.QueryRequest;
import com.XYai.myai.core.dto.RagResponse;

import java.util.concurrent.CompletableFuture;

/**
 * RAG 编排器接口，定义从请求到答案的端到端流程。
 */
@FunctionalInterface
public interface RAGOrchestrator {

    /**
     * 异步执行一次 RAG 问答流程。
     *
     * @param request 查询请求
     * @return 异步答案结果
     */
    CompletableFuture<RagResponse> answer(QueryRequest request);
}

