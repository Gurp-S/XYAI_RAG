package com.XYai.myai.impl;

import com.XYai.myai.core.dto.DocumentChunk;
import com.XYai.myai.core.dto.RetrievalResult;
import com.XYai.myai.core.dto.SearchResult;
import com.XYai.myai.core.retriever.Retriever;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 检索器桩实现，返回固定格式的示例文档。
 */
@Service
public class StubRetriever implements Retriever {

    /**
     * 根据查询构造一条模拟检索命中。
     *
     * @param query 查询文本
     * @param topK 期望返回数量（桩实现中未使用）
     * @param hints 检索提示参数（桩实现中未使用）
     * @return 仅包含一条示例结果的异步检索响应
     */
    @Override
    public CompletableFuture<RetrievalResult> retrieve(String query, int topK, Map<String, Object> hints) {
        DocumentChunk doc = new DocumentChunk("stub-1", "stub", "示例文档内容: " + query, Map.of());
        SearchResult sr = new SearchResult(doc, 0.9);
        return CompletableFuture.completedFuture(new RetrievalResult(List.of(sr), Map.of()));
    }
}

