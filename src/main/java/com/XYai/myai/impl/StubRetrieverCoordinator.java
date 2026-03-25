package com.XYai.myai.impl;

import com.XYai.myai.core.dto.RetrievalResult;
import com.XYai.myai.core.dto.SearchResult;
import com.XYai.myai.core.retriever.Retriever;
import com.XYai.myai.core.retriever.RetrieverCoordinator;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * 多路检索协调桩实现。
 */
@Service
public class StubRetrieverCoordinator implements RetrieverCoordinator {

    /**
     * 并行执行多检索器并进行去重、排序、截断。
     *
     * @param query 查询文本
     * @param topK 最终保留的结果数
     * @param retrievers 参与检索的检索器列表
     * @return 聚合后的异步检索结果
     */
    @Override
    public CompletableFuture<RetrievalResult> retrieveMulti(String query, int topK, List<Retriever> retrievers) {
        List<CompletableFuture<RetrievalResult>> futures = retrievers.stream()
                .map(r -> r.retrieve(query, topK, Map.of()))
                .toList();

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<SearchResult> merged = new ArrayList<>();
                    for (CompletableFuture<RetrievalResult> f : futures) {
                        RetrievalResult rr = f.join();
                        if (rr != null && rr.hits() != null) {
                            merged.addAll(rr.hits());
                        }
                    }
                    // dedupe by doc id (keep the highest score)
                    Map<String, SearchResult> best = new HashMap<>();
                    for (SearchResult s : merged) {
                        String id = s.doc().id();
                        best.merge(id, s, (a, b) -> a.score() >= b.score() ? a : b);
                    }
                    List<SearchResult> out = new ArrayList<>(best.values());
                    out.sort(Comparator.comparingDouble(SearchResult::score).reversed());
                    if (out.size() > topK) out = out.subList(0, topK);
                    return new RetrievalResult(out, Map.of());
                });
    }
}


