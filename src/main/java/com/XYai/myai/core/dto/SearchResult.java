package com.XYai.myai.core.dto;

/**
 * 单条检索命中项。
 *
 * @param doc 命中文档分块
 * @param score 相关性得分
 */
public record SearchResult(DocumentChunk doc, double score) {
}

