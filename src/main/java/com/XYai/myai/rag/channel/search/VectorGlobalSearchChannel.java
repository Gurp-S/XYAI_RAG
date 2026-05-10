package com.XYai.myai.rag.channel.search;

import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchChannel;
import com.XYai.myai.rag.channel.pojo.SearchChannelResult;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.ByteString;
import io.milvus.client.MilvusClient;
import io.milvus.grpc.FieldData;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.dml.SearchParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Component
public class VectorGlobalSearchChannel implements SearchChannel {

    private static final double SIMILARITY_THRESHOLD = 0.5;   // COSINE 相似度阈值
    private static final int DEFAULT_TOP_K = 5;

    @Resource
    private EmbeddingModel embeddingModel;

    @Resource
    private MilvusClient milvusClient;

    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String defaultCollectionName;

    @Value("${spring.ai.vectorstore.milvus.databaseName:my_xy}")
    private String databaseName;

    @Resource(name = "searchChannelExecutor")
    private ThreadPoolTaskExecutor searchChannelExecutor;

    @Override
    public String getName() {
        return "vector-global-search";
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public String getType() {
        return "vector-global-search";
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        return true;   // 全局检索始终启用（原意图逻辑已移除）
    }

    @Override
    public SearchChannelResult search(SearchContext context) {
        // 获取查询列表
        RewriteResult rewriteResult = Optional.ofNullable(context.getRewriteQuestion())
                .orElse(RewriteResult.builder()
                        .rewrittenQuery(context.getOriginalQuery())
                        .subQuery(null)
                        .build());
        if (rewriteResult == null) {
            return emptyResult();
        }

        List<String> queryList = Optional.ofNullable(rewriteResult.getSubQuery())
                .filter(list -> !list.isEmpty())
                .orElse(List.of(rewriteResult.getRewrittenQuery()));

        // 并行多查询检索
        List<CompletableFuture<List<RetrievedChunk>>> futures = queryList.stream()
                .map(query -> CompletableFuture.supplyAsync(
                        () -> searchWithNativeMilvus(query),
                        searchChannelExecutor
                ))
                .toList();

        List<RetrievedChunk> allChunks = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(RetrievedChunk::getScore).reversed())
                .limit(DEFAULT_TOP_K)
                .collect(Collectors.toList());

        return SearchChannelResult.builder()
                .channelName(getName())
                .chunks(allChunks)
                .metadata(allChunks.stream().map(RetrievedChunk::getMetadata).toList())
                .build();
    }

    /**
     * 原生 Milvus 检索，精准匹配 Indexer 写入的字段：
     * - embedding 用于 ANN 搜索
     * - content 作为返回的文本
     * - metadata 作为元数据（JSON 字符串）
     */
    private List<RetrievedChunk> searchWithNativeMilvus(String query) {
        try {
            float[] vectorArray = embeddingModel.embed(query);
            List<Float> vectorList = IntStream.range(0, vectorArray.length)
                    .mapToObj(i -> vectorArray[i])
                    .toList();

            // 2. 原生搜索
            SearchParam searchParam = SearchParam.newBuilder()
                    .withDatabaseName(databaseName)
                    .withCollectionName(defaultCollectionName)
                    .withVectorFieldName("embedding")
                    .withFloatVectors(Collections.singletonList(vectorList))
                    .withTopK(DEFAULT_TOP_K)
                    .withMetricType(MetricType.COSINE)
                    .withOutFields(Arrays.asList("content", "metadata"))   // 只取需要的字段
                    .build();

            SearchResults results = milvusClient.search(searchParam).getData();
            // 3. 解析结果
            List<Float> scores = results.getResults().getScoresList();
            List<FieldData> fieldsDataList = results.getResults().getFieldsDataList();

            List<String> contentList = null;
            List<String> metadataJsonList = null;

            for (FieldData fieldData : fieldsDataList) {
                if ("content".equals(fieldData.getFieldName())) {
                    contentList = fieldData.getScalars().getStringData().getDataList();
                } else if ("metadata".equals(fieldData.getFieldName())) {
                    List<ByteString> byteStrings = fieldData.getScalars().getJsonData().getDataList();
                    metadataJsonList = byteStrings.stream()
                            .map(ByteString::toStringUtf8)
                            .collect(Collectors.toList());
                }
            }
            log.info("召回的内容:{},元数据:{}",contentList,metadataJsonList);
            // 防御性处理
            if (contentList == null) {
                contentList = Collections.emptyList();
            }
            if (metadataJsonList == null || metadataJsonList.isEmpty()) {
                metadataJsonList = Collections.nCopies(contentList.size(), "{}");
            }
            if (scores.isEmpty()) {
                scores = Collections.nCopies(contentList.size(), 0.0f);
            }

            List<RetrievedChunk> chunks = new ArrayList<>();
            for (int i = 0; i < contentList.size(); i++) {
                double score = (i < scores.size()) ? scores.get(i) : 0.0;
                String metadataJson = i < metadataJsonList.size() ? metadataJsonList.get(i) : "{}";
                Map<String, Object> metadata = parseMetadata(metadataJson);
                chunks.add(RetrievedChunk.builder()
                        .content(contentList.get(i))
                        .score(score)
                        .metadata(metadata)
                        .build());
            }
            log.info("向量召回文档分数:{}",chunks.stream().map(RetrievedChunk::getScore).toList());
            return chunks;
        } catch (Exception e) {
            log.error("原生 Milvus 检索失败", e);
            return List.of();
        }
    }

    /**
     * 将 metadata JSON 字符串解析为 Map
     */
    private Map<String, Object> parseMetadata(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyMap();
        try {
            JSONObject obj = JSON.parseObject(raw);
            return new HashMap<>(obj);
        } catch (Exception e) {
            log.warn("解析 metadata JSON 失败: {}", raw, e);
            return Collections.emptyMap();
        }
    }

    private SearchChannelResult emptyResult() {
        return SearchChannelResult.builder()
                .channelName(getName())
                .chunks(Collections.emptyList())
                .metadata(Collections.emptyList())
                .build();
    }
}