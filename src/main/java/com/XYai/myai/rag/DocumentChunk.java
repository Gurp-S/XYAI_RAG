package com.XYai.myai.rag;

import java.util.Map;

/**
 * 文档分块实体。
 *
 * @param id       分块 ID
 * @param source   来源标识
 * @param text     分块正文
 * @param metadata 分块元数据
 */
public record DocumentChunk(
        String id,
        String source,
        String text,
        Map<String, Object> metadata
) {
}

