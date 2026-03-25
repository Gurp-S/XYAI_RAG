package com.XYai.myai.core.dto;

import java.util.Map;

/**
 * Request DTO for RAG queries.
 */
public record QueryRequest(
        String userId,
        String text,
        String conversationId,
        Map<String, Object> extra
) {
}

