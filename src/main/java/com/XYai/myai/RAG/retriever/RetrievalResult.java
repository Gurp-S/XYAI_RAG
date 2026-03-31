package com.XYai.myai.RAG.retriever;

import com.XYai.myai.RAG.SearchResult;

import java.util.List;
import java.util.Map;

/**
 * 检索聚合结果。
 *
 * @param hits 命中文档列表
 * @param meta 附加元信息
 */
public record RetrievalResult(
        List<SearchResult> hits,
        Map<String, Object> meta
) {
}

