package com.XYai.myai.rag.milvus;

import com.XYai.myai.rag.milvus.pojo.MilvusMetadata;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

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
     * 最终数据过滤：只保留允许显示的字段 + 字符串超长截断 + 清洗数据 + 排序
     */
    public List<Map<String, Object>> showFilter(List<Map<String, Object>> rawList) {
        if (rawList == null || rawList.isEmpty()) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> raw : rawList) {
            if (raw == null) continue;
            Map<String, Object> filtered = new HashMap<>();
            // 白名单字段
            MilvusMetadata.METADATA_SHOW.forEach(key -> {
                Object val = raw.get(key);
                if (val != null) {
                    filtered.put(key, val instanceof String s
                            ? s.length() > MAX_STRING_LENGTH ? s.substring(0, MAX_STRING_LENGTH) + "..." : s
                            : val);
                }
            });
            // 保留必要字段
            filtered.put("doc_id", raw.get("doc_id"));
            filtered.put("content", raw.get("content"));
            filtered.put("metadata", raw.get("metadata"));
            result.add(filtered);
        }
        return result;
    }

    /**
     * 过滤单个 Document
     */
    public Document filter(Document document) {
        if (document == null || document.getText() == null) {
            return null;
        }

        Map<String, Object> cleanedMetadata = filter(document.getMetadata());

        return document.mutate()
                .metadata(cleanedMetadata)
                .build();
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


    public static Map<String, Object> sanitize(Map<String, Object> metadata, Map<String, String> typeHints) {
        Map<String, Object> normalized = new HashMap<>();
        if (metadata == null || metadata.isEmpty()) return normalized;

        for (Map.Entry<String, Object> e : metadata.entrySet()) {
            String key = e.getKey();
            Object val = e.getValue();
            String hint = typeHints == null ? null : typeHints.get(key);

            try {
                if (hint != null) {
                    switch (hint) {
                        case "long" -> {
                            Long v = tryParseLong(val);
                            if (v != null) normalized.put(key, v);
                            else if (val != null) normalized.put(key, val.toString());
                        }
                        case "int" -> {
                            Long v = tryParseLong(val);
                            if (v != null) normalized.put(key, v.intValue());
                            else if (val != null) normalized.put(key, val.toString());
                        }
                        case "boolean" -> {
                            Boolean b = tryParseBoolean(val);
                            if (b != null) normalized.put(key, b);
                            else if (val != null) normalized.put(key, val.toString());
                        }
                        case "lowercase" -> normalized.put(key, val == null ? null : val.toString().trim().toLowerCase());
                        case "string", "date" -> normalized.put(key, val == null ? null : val.toString());
                        default -> normalized.put(key, val == null ? null : val.toString());
                    }
                } else {
                    // no hint: try to keep numbers/booleans, otherwise toString()
                    if (val instanceof Number || val instanceof Boolean) {
                        normalized.put(key, val);
                    } else if (val == null) {
                        normalized.put(key, null);
                    } else {
                        String className = val.getClass().getName();
                        // convert complex gson structures or maps/lists to string to avoid client-side types
                        if (className.contains("google.gson") || val instanceof Map || val instanceof List) {
                            normalized.put(key, val.toString());
                        } else {
                            normalized.put(key, val.toString());
                        }
                    }
                }
            } catch (Exception ex) {
                if (val != null) normalized.put(key, val.toString());
            }
        }
        return normalized;
    }

    public static void sanitizeDocuments(List<Document> documents, Map<String, String> typeHints) {
        if (documents == null || documents.isEmpty()) return;
        for (Document doc : documents) {
            try {
                Map<String, Object> meta = doc.getMetadata();
                Map<String, Object> normalized = sanitize(meta, typeHints);
                if (meta == null) continue;
                try {
                    meta.clear();
                    meta.putAll(normalized);
                } catch (UnsupportedOperationException uoe) {
                }
            } catch (Exception ex) {
            }
        }
    }

    private static Long tryParseLong(Object o) {
        if (o == null) return null;
        if (o instanceof Number) return ((Number) o).longValue();
        String s = o.toString().trim();
        if (s.isEmpty()) return null;
        try {
            if (s.contains(".")) {
                // handle "1.0" -> 1
                BigDecimal d = new BigDecimal(s);
                return d.longValue();
            }
            return Long.parseLong(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Boolean tryParseBoolean(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean) return (Boolean) o;
        String s = o.toString().trim().toLowerCase();
        if (s.isEmpty()) return null;
        if (s.equals("true") || s.equals("1") || s.equals("yes")) return true;
        if (s.equals("false") || s.equals("0") || s.equals("no")) return false;
        return null;
    }
}
