package com.XYai.myai.RAG;

import java.util.List;
import java.util.Map;

/**
 * RAG 回答结果。
 *
 * @param answer 最终答案文本
 * @param usedChunks 回答引用的文档分块
 * @param debug 调试信息（可选）
 */
public record RagResponse(
        String answer,
        List<DocumentChunk> usedChunks,
        Map<String, Object> debug
) {
}

