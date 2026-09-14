package com.XYai.myai.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 百炼原生多模态 Embedding 适配器（tongyi-embedding-vision 系列）。
 *
 * 背景：vision 系列 embedding 不支持 OpenAI 兼容端点，只能调原生
 * multimodal-embedding API；向量维度 768（text-embedding-v4 为 1024），
 * 切换时必须同步修改 spring.ai.vectorstore.milvus.embedding-dimension 并重建集合重索引。
 *
 * 通过 rag.embedding.provider=dashscope-vision 启用（@Primary 条件装配），
 * 未启用时走 OpenAI（text-embedding-v4）。
 */
@Slf4j
public class DashscopeVisionEmbeddingModel implements EmbeddingModel {

    /** 原生 API 单次请求 contents 上限的保守取值 */
    private static final int MAX_BATCH = 10;
    private static final int MAX_RETRIES = 2;

    private final RestClient restClient;
    private final String model;
    private final int dimensions;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DashscopeVisionEmbeddingModel(String apiKey, String baseUrl, String model, int dimensions) {
        this.model = model;
        this.dimensions = dimensions;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>(inputs.size());
        long totalTokens = 0;

        for (int from = 0; from < inputs.size(); from += MAX_BATCH) {
            List<String> batch = inputs.subList(from, Math.min(from + MAX_BATCH, inputs.size()));
            BatchResult result = embedBatch(batch, 0);
            totalTokens += result.inputTokens;
            for (int i = 0; i < result.vectors.size(); i++) {
                embeddings.add(new Embedding(result.vectors.get(i), from + i));
            }
        }

        long finalTotalTokens = totalTokens;
        Usage usage = new Usage() {
            @Override
            public Integer getPromptTokens() {
                return (int) finalTotalTokens;
            }

            @Override
            public Integer getCompletionTokens() {
                return 0;
            }

            @Override
            public Integer getTotalTokens() {
                return (int) finalTotalTokens;
            }

            @Override
            public Object getNativeUsage() {
                return finalTotalTokens;
            }
        };
        return new EmbeddingResponse(embeddings, new EmbeddingResponseMetadata(model, usage));
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    /** 覆盖默认实现：避免维度探测时额外调用一次 API */
    @Override
    public int dimensions() {
        return dimensions;
    }

    private BatchResult embedBatch(List<String> texts, int attempt) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            ObjectNode input = body.putObject("input");
            ArrayNode contents = input.putArray("contents");
            for (String text : texts) {
                contents.addObject().put("text", text == null ? "" : text);
            }

            JsonNode resp = restClient.post()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode embNode = resp.path("output").path("embeddings");
            if (!embNode.isArray() || embNode.isEmpty()) {
                throw new IllegalStateException("multimodal-embedding 返回缺少 embeddings: "
                        + resp.toString().substring(0, Math.min(200, resp.toString().length())));
            }
            // 按 index 排序，保证与输入顺序一致
            List<JsonNode> sorted = new ArrayList<>();
            embNode.forEach(sorted::add);
            sorted.sort(Comparator.comparingInt(n -> n.path("index").asInt()));

            BatchResult result = new BatchResult();
            for (JsonNode n : sorted) {
                float[] v = new float[n.path("embedding").size()];
                int i = 0;
                for (JsonNode x : n.path("embedding")) {
                    v[i++] = x.floatValue();
                }
                result.vectors.add(v);
            }
            result.inputTokens = resp.path("usage").path("input_tokens").asLong(0);
            return result;
        } catch (Exception e) {
            if (attempt < MAX_RETRIES - 1) {
                log.warn("[VisionEmbedding] 批量调用失败(第{}次)，准备重试: {}", attempt + 1, e.getMessage());
                return embedBatch(texts, attempt + 1);
            }
            throw new RuntimeException("multimodal-embedding 调用失败(重试" + MAX_RETRIES + "次后)", e);
        }
    }

    private static class BatchResult {
        final List<float[]> vectors = new ArrayList<>();
        long inputTokens;
    }
}
