package com.XYai.myai.rag.channel.search;

import com.XYai.myai.commonUtils.FloatArrayList;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchChannel;
import com.XYai.myai.rag.channel.pojo.SearchChannelResult;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.google.protobuf.ByteString;
import io.milvus.client.MilvusClient;
import io.milvus.grpc.FieldData;
import io.milvus.grpc.SearchResults;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.dml.SearchParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
@Component
public class VectorGlobalSearchChannel implements SearchChannel {

    private static final double SIMILARITY_THRESHOLD = 0.5;   // COSINE 相似度阈值
    private static final int DEFAULT_TOP_K = 5;
    // 向量字段名
    private static final String VECTOR_FIELD_CONTEXT = "embedding_context";
    private static final String VECTOR_FIELD_QUESTION = "embedding_question";
    @Resource
    private EmbeddingModel embeddingModel;
    @Resource
    private MilvusClient milvusClient;
    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String defaultCollectionName;
    @Value("${spring.ai.vectorstore.milvus.databaseName:my_xy}")
    private String databaseName;
    @Resource(name = "searchChannelExecutor")
    private TaskExecutor searchChannelExecutor;

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
        return true;
    }

    @Override
    @RagTraceNode(name = "向量召回", type = "search" , taskIdArg = "searchRoot")
    public SearchChannelResult search(SearchContext context) {
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
                .orElseGet(() -> {
                    String q = rewriteResult.getRewrittenQuery();
                    return (q != null && !q.isBlank()) ? List.of(q) : List.of(context.getOriginalQuery());
                });

        // 并行多查询检索
        long t0 = System.currentTimeMillis();
        List<float[]> queryVectorList = embeddingModel.embed(queryList);
        long t1 = System.currentTimeMillis();
        log.info("向量问题时间: {}ms", t1 - t0);
        List<CompletableFuture<List<RetrievedChunk>>> futures = queryVectorList.stream()
                .map(query -> CompletableFuture.supplyAsync(
                        () -> searchWithDualVectors(query),
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
     * 双向量搜索 + 合并（取每个 chunk 在两个字段中的最大余弦分数）
     */
    private List<RetrievedChunk> searchWithDualVectors(float[] queryVector) {
        try {
            List<Float> vectorList = new FloatArrayList(queryVector);

            // 并行搜索两个向量字段
            long t2 = System.currentTimeMillis();
            CompletableFuture<List<RetrievedChunk>> contextFuture = CompletableFuture.supplyAsync(
                    () -> searchByField(vectorList, VECTOR_FIELD_CONTEXT), searchChannelExecutor);
            CompletableFuture<List<RetrievedChunk>> questionFuture = CompletableFuture.supplyAsync(
                    () -> searchByField(vectorList, VECTOR_FIELD_QUESTION), searchChannelExecutor);

            List<RetrievedChunk> contextResults = contextFuture.join();
            log.info("文本搜索分数:{}",contextResults.stream().map(RetrievedChunk::getScore).toList());
            long t3 = System.currentTimeMillis();
            List<RetrievedChunk> questionResults = questionFuture.join();
            long t4 = System.currentTimeMillis();
            log.info("问题搜索分数:{}",contextResults.stream().map(RetrievedChunk::getScore).toList());
            log.info("文本搜索 {}ms, 问题搜索 search {}ms, merge overhead {}ms", t3 - t2, t4 - t3, t4 - t2);
            // 合并：以 "fileId:chunkId" 为唯一键，保留分数最高的 chunk
            Map<String, RetrievedChunk> merged = new LinkedHashMap<>();

            Consumer<List<RetrievedChunk>> mergeList = list -> {
                for (RetrievedChunk chunk : list) {
                    String key = buildUniqueKey(chunk);
                    if (key == null) continue;
                    RetrievedChunk existing = merged.get(key);
                    if (existing == null || chunk.getScore() > existing.getScore()) {
                        merged.put(key, chunk);
                    }
                }
            };

            mergeList.accept(contextResults);
            mergeList.accept(questionResults);

            List<RetrievedChunk> finalChunks = new ArrayList<>(merged.values());
            finalChunks.sort(Comparator.comparingDouble(RetrievedChunk::getScore).reversed());

            log.info("双向量融合后分数: {}", finalChunks.stream().map(RetrievedChunk::getScore).toList());
            return finalChunks;

        } catch (Exception e) {
            log.error("双向量检索失败", e);
            return List.of();
        }
    }

    /**
     * 针对指定向量字段进行一次搜索
     */
    private List<RetrievedChunk> searchByField(List<Float> vectorList, String vectorFieldName) {
        SearchParam searchParam = SearchParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(defaultCollectionName)
                .withVectorFieldName(vectorFieldName)
                .withFloatVectors(Collections.singletonList(vectorList))
                .withTopK(DEFAULT_TOP_K)
                .withMetricType(MetricType.COSINE)
                .withOutFields(Arrays.asList("doc_id", "content", "metadata"))
                .build();

        R<SearchResults> searchResponse = milvusClient.search(searchParam);
        if (searchResponse.getStatus() != R.Status.Success.getCode()) {
            log.warn("[向量搜索] {} 搜索失败: {}", vectorFieldName, searchResponse.getMessage());
            return List.of();
        }
        SearchResults results = searchResponse.getData();
        if (results == null) {
            log.warn("[向量搜索] {} 返回空结果", vectorFieldName);
            return List.of();
        }

        List<Float> scores = results.getResults().getScoresList();

        List<FieldData> fieldsDataList = results.getResults().getFieldsDataList();
        if (fieldsDataList.isEmpty()) {
            log.warn("[向量搜索] {} 无字段数据, scores={}", vectorFieldName, scores.size());
            // 即使没有字段数据，也可能有分数（纯向量搜索）
            if (scores.isEmpty()) return List.of();
        }

        List<String> docIdList = null;
        List<String> contentList = null;
        List<String> metadataJsonList = null;

        for (FieldData fieldData : fieldsDataList) {
            String fieldName = fieldData.getFieldName();
            if ("doc_id".equals(fieldName)) {
                docIdList = fieldData.getScalars().getStringData().getDataList();
            } else if ("content".equals(fieldName)) {
                contentList = fieldData.getScalars().getStringData().getDataList();
            } else if ("metadata".equals(fieldName)) {
                List<ByteString> byteStrings = fieldData.getScalars().getJsonData().getDataList();
                metadataJsonList = byteStrings.stream()
                        .map(ByteString::toStringUtf8)
                        .toList();
            }
        }

        if (docIdList == null) docIdList = Collections.emptyList();
        if (contentList == null) contentList = Collections.emptyList();
        if (metadataJsonList == null || metadataJsonList.isEmpty())
            metadataJsonList = Collections.nCopies(contentList.size(), "{}");
        if (scores.isEmpty()) scores = Collections.nCopies(contentList.size(), 0.0f);
        List<RetrievedChunk> chunks = new ArrayList<>(contentList.size());
        for (int i = 0; i < contentList.size(); i++) {
            double score = i < scores.size() ? scores.get(i) : 0.0;
            String metadataJson = i < metadataJsonList.size() ? metadataJsonList.get(i) : "{}";
            Map<String, Object> metadata = parseMetadata(metadataJson);
            String docId = i < docIdList.size() ? docIdList.get(i) : null;
            if (docId != null && !docId.isBlank()) {
                metadata.put("doc_id", docId);
            }
            chunks.add(RetrievedChunk.builder()
                    .id(docId)
                    .content(contentList.get(i))
                    .score(score)
                    .metadata(metadata)
                    .build());
        }
        return chunks;
    }

    /**
     * 构建唯一标识：优先使用 doc_id（Milvus 主键）。
     */
    private String buildUniqueKey(RetrievedChunk chunk) {
        if (chunk == null) return null;
        if (chunk.getId() != null && !chunk.getId().isBlank()) {
            return chunk.getId();
        }
        Map<String, Object> meta = chunk.getMetadata();
        if (meta == null) return null;
        Object docId = meta.get("doc_id");
        if (docId == null) return null;
        String s = String.valueOf(docId);
        return s.isBlank() ? null : s;
    }

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