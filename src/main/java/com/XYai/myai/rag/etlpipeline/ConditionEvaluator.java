package com.XYai.myai.rag.etlpipeline;

import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ConditionEvaluator {

    /**
     * 最小学习版条件：
     * - 空条件 => true
     * - {"enabled": false} => false
     * - {"field":"mimeType", "equals":"application/pdf"} => 按字段匹配
     */
    public boolean evaluate(JsonNode condition, IngestionContext context) {
        if (condition == null || condition.isNull() || condition.isEmpty()) {
            return true;
        }

        JsonNode enabled = condition.get("enabled");
        if (enabled != null && enabled.isBoolean()) {
            return enabled.asBoolean();
        }

        JsonNode field = condition.get("field");
        JsonNode equals = condition.get("equals");
        if (field != null && equals != null && field.isTextual()) {
            String actual = resolve(field.asText(), context);
            return equals.asText().equals(actual);
        }

        return true;
    }

    private String resolve(String field, IngestionContext context) {
        Document document = context.getDocument();
        if (document == null) {
            return null;
        }

        return switch (field) {
            case "mimeType" -> toStringOrNull(document.getMetadata().get(IngestionContext.META_MIME_TYPE));
            case "source.type" -> toStringOrNull(document.getMetadata().get(IngestionContext.META_SOURCE_TYPE));
            case "source.uri" -> toStringOrNull(document.getMetadata().get(IngestionContext.META_SOURCE_URI));
            case "hasRawText" -> String.valueOf(StringUtils.hasText(document.getText()));
            case "hasEnhancedText" -> String.valueOf(StringUtils.hasText(
                    toStringOrNull(document.getMetadata().get(IngestionContext.META_ENHANCED))
            ));
            default -> null;
        };
    }

    private String toStringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

