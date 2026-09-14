package com.XYai.myai.commonUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.*;

/**
 * 百炼原生 Rerank 服务（gte-rerank 系列）。
 *
 * <p>背景：原 {@link OllamaRerankerService} 依赖本地 Ollama（/v1/rerank），
 * 生产环境未安装 Ollama 时重排永远降级为混合分数。百炼提供原生
 * text-rerank API（gte-rerank-v2 等），与已有 API Key 通用，作为默认重排提供方。</p>
 *
 * <p>API：POST {baseUrl}/api/v1/services/rerank/text-rerank/text-rerank
 * <pre>{ "model": "gte-rerank-v2",
 *       "input": { "query": "...", "documents": ["...", ...] },
 *       "parameters": { "return_documents": false, "top_n": N } }</pre>
 * 响应：output.results[{index, relevance_score}]，relevance_score ∈ [0,1]。</p>
 */
public class DashscopeRerankerService {

    private static final Logger log = LoggerFactory.getLogger(DashscopeRerankerService.class);

    /** 单次请求文档数上限（gte-rerank 系列限制的保守取值） */
    private static final int MAX_BATCH_DOCS = 100;

    private final RestClient restClient;
    private final String model;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 重排结果缓存：key = query + 文档内容哈希。
     * 高并发下相同查询-文档组合直接命中，消除对外部 rerank API 的重复调用
     * （远程限速是压测中的首要吞吐瓶颈）。
     */
    private final com.github.benmanes.caffeine.cache.Cache<String, List<Double>> rerankCache =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumSize(50_000)
                    .expireAfterWrite(java.time.Duration.ofMinutes(10))
                    .build();

    public DashscopeRerankerService(String apiKey, String baseUrl, String model) {
        this.model = model;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    /**
     * 对查询-文档列表进行重排序，返回与输入顺序一致的分数列表。
     *
     * @param query     查询文本
     * @param documents 候选文档列表（保持顺序）
     * @return 与 documents 顺序一致的 relevance_score 列表；调用失败返回空列表（调用方降级）
     */
    public List<Double> rerank(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }
        String cacheKey = cacheKey(query, documents);
        List<Double> cached = rerankCache.getIfPresent(cacheKey);
        if (cached != null && cached.size() == documents.size()) {
            return cached;
        }
        List<Double> result = rerankRemote(query, documents);
        if (!result.isEmpty()) {
            rerankCache.put(cacheKey, result);
        }
        return result;
    }

    private String cacheKey(String query, List<String> documents) {
        StringBuilder sb = new StringBuilder(query == null ? "" : query).append('#');
        for (String d : documents) {
            sb.append(d == null ? 0 : d.hashCode()).append(',');
        }
        return sb.toString();
    }

    private List<Double> rerankRemote(String query, List<String> documents) {
        double[] scores = new double[documents.size()];
        // 超过单次上限时分批调用
        for (int from = 0; from < documents.size(); from += MAX_BATCH_DOCS) {
            int end = Math.min(from + MAX_BATCH_DOCS, documents.size());
            List<String> batch = documents.subList(from, end);
            List<double[]> partial = rerankBatch(query, batch);
            // partial 为 null 表示调用失败，整体降级
            if (partial == null) {
                return Collections.emptyList();
            }
            for (double[] pair : partial) {
                int idx = from + (int) pair[0];
                if (idx >= 0 && idx < scores.length) {
                    scores[idx] = pair[1];
                }
            }
        }

        List<Double> result = new ArrayList<>(documents.size());
        for (double s : scores) {
            result.add(s);
        }
        return result;
    }

    /**
     * 单批次调用，返回 [index(批内), score] 对；失败返回 null。
     */
    private List<double[]> rerankBatch(String query, List<String> documents) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            ObjectNode input = body.putObject("input");
            input.put("query", query == null ? "" : query);
            ArrayNode docs = input.putArray("documents");
            for (String doc : documents) {
                docs.add(doc == null ? "" : doc);
            }
            ObjectNode parameters = body.putObject("parameters");
            parameters.put("return_documents", false);
            parameters.put("top_n", documents.size());

            JsonNode resp = restClient.post()
                    .uri("/api/v1/services/rerank/text-rerank/text-rerank")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode results = resp.path("output").path("results");
            if (!results.isArray()) {
                log.warn("[DashscopeRerank] 响应缺少 output.results: {}",
                        resp.toString().substring(0, Math.min(200, resp.toString().length())));
                return null;
            }
            List<double[]> pairs = new ArrayList<>(results.size());
            for (JsonNode n : results) {
                pairs.add(new double[]{n.path("index").asInt(-1), n.path("relevance_score").asDouble(0.0)});
            }
            return pairs;
        } catch (Exception e) {
            log.warn("[DashscopeRerank] 调用失败，降级: {}", e.getMessage());
            return null;
        }
    }
}
