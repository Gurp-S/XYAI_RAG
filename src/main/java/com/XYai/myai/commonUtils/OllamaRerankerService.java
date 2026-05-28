package com.XYai.myai.commonUtils;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.*;

@Service
public class OllamaRerankerService {

    private final RestClient restClient;

    @Value("${ollama.rerank.model:qwen3-reranker-0.6b}")   // 默认模型名
    private String model;

    public OllamaRerankerService(@Value("${ollama.base-url:http://localhost:11434}") String baseUrl) {
        this.restClient = RestClient.create(baseUrl);
    }

    /**
     * 对查询-文档列表进行重排序，返回原始顺序的分数列表
     * @param query 用户查询
     * @param documents 候选文档列表（保持顺序）
     * @return 与 documents 顺序一致的分数列表
     */
    public List<Double> rerank(String query, List<String> documents) {
        if (documents == null || documents.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("query", query);
        requestBody.put("documents", documents);

        // Ollama V1 Rerank API 返回格式：{ "results": [ { "index": 0, "score": 0.98 }, ... ] }
        OllamaRerankResponse response = restClient.post()
                .uri("/v1/rerank")
                .body(requestBody)
                .retrieve()
                .body(OllamaRerankResponse.class);

        // 将结果按 index 排序，确保返回的分数列表与输入 documents 顺序一致
        double[] scores = new double[documents.size()];
        if (response != null && response.results() != null) {
            for (OllamaRerankResponse.Result result : response.results()) {
                if (result.index() >= 0 && result.index() < scores.length) {
                    scores[result.index()] = result.score();
                }
            }
        }
        return new DoubleArrayList(scores);
    }

    // 响应体映射
    private record OllamaRerankResponse(List<Result> results) {
        record Result(int index, double score) {}
    }
}