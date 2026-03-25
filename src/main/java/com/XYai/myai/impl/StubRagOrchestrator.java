package com.XYai.myai.impl;

import com.XYai.myai.core.dto.QueryRequest;
import com.XYai.myai.core.dto.RagResponse;
import com.XYai.myai.core.dto.RetrievalResult;
import com.XYai.myai.core.intent.IntentRecognitionService;
import com.XYai.myai.core.retriever.Retriever;
import com.XYai.myai.core.retriever.RetrieverCoordinator;
import com.XYai.myai.core.rewrite.QueryRewriter;
import com.XYai.myai.core.rag.RAGOrchestrator;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * RAG 编排器桩实现，用于开发阶段联调主流程。
 */
@Service
public class StubRagOrchestrator implements RAGOrchestrator {

    private final IntentRecognitionService intentService;
    private final QueryRewriter rewriter;
    private final RetrieverCoordinator coordinator;

    /**
     * 构造编排器桩实现。
     *
     * @param intentService 意图识别服务
     * @param rewriter 查询重写服务
     * @param coordinator 多路检索协调器
     */
    public StubRagOrchestrator(IntentRecognitionService intentService, QueryRewriter rewriter, RetrieverCoordinator coordinator) {
        this.intentService = intentService;
        this.rewriter = rewriter;
        this.coordinator = coordinator;
    }

    /**
     * 执行一次简化版 RAG 流程并返回桩答案。
     *
     * @param request 查询请求
     * @return 异步 RAG 响应
     */
    @Override
    public CompletableFuture<RagResponse> answer(QueryRequest request) {
        var intent = intentService.recognize(request.userId(), request.text());
        String rewritten = rewriter.rewrite(request.userId(), request.text(), Map.of("intent", intent.intent()));
        // find retrievers from context or use all available - for stub fetch from Spring via coordinator caller
        List<Retriever> retrievers = List.of(); // callers should inject or coordinator may have internal defaults

        CompletableFuture<RetrievalResult> rr = coordinator.retrieveMulti(rewritten, 5, retrievers);

        return rr.thenApply(r -> {
            var used = r.hits().stream().map(h -> h.doc()).toList();
            String answer = "[stub answer] based on " + used.size() + " docs and intent=" + intent.intent();
            return new RagResponse(answer, used, Map.of("intent", intent.intent()));
        });
    }
}


