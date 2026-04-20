package com.XYai.myai.rag.milvus;

import com.XYai.myai.rag.milvus.POJO.MilvusMetadata;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public final class MilvusMetadataFilter {

    private static final int MAX_STRING_LENGTH = 2000;

    private static final Set<String> ALLOWED_METADATA_KEYS = MilvusMetadata.METADATA_FIELDS;

    /**
     * 过滤单个 metadata
     */
    public Map<String, Object> filter(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }

        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (allowMetadataKey(key, value)) {
                result.put(key, normalizeValue(value));
            }
        }
        return result;
    }

    /**
     * 过滤单个 Document
     */
    public Document filter(Document document) {
        if (document == null|| document.getText()==null) {
            return null;
        }

        Map<String, Object> cleanedMetadata = filter(document.getMetadata());

        // Spring AI Document
        return new Document(document.getText(), cleanedMetadata);
    }

    /**
     * 过滤一组 Document
     */
    public List<Document> filter(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }
        List<Document> result = new ArrayList<>(documents.size());
        for (Document document : documents) {
            Document cleaned = filter(document);
            if (cleaned != null) {
                result.add(cleaned);
            }
        }
        return result;
    }

    /**
     * 判断字段是否允许保留
     */
    private boolean allowMetadataKey(String key, Object value) {
        if (key == null || !ALLOWED_METADATA_KEYS.contains(key) || value == null) {
            return false;
        }

        if (value instanceof Map<?, ?> || value instanceof List<?>) {
            return false;
        }

        if (value instanceof String s) {
            return !s.isBlank() && s.length() <= MAX_STRING_LENGTH;
        }

        return value instanceof Number || value instanceof Boolean;
    }

    /**
     * 规范化保留值
     * 目前主要用于字符串裁剪，其他类型原样返回
     */
    private Object normalizeValue(Object value) {
        if (value instanceof String s) {
            if (s.length() <= MAX_STRING_LENGTH) {
                return s;
            }
            return s.substring(0, MAX_STRING_LENGTH);
        }
        return value;
    }
}
