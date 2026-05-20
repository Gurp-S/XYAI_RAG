package com.XYai.myai.rag.channel.search;

import com.XYai.myai.rag.aop.annotation.RagTraceContext;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchChannel;
import com.XYai.myai.rag.channel.pojo.SearchChannelResult;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import com.XYai.myai.rag.graph.EntityRecognizer;
import com.XYai.myai.rag.graph.Neo4jKnowledgeGraphService;
import com.XYai.myai.rag.graph.pojo.CommunitySearchResult;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.google.protobuf.ByteString;
import io.milvus.client.MilvusClient;
import io.milvus.grpc.FieldData;
import io.milvus.grpc.QueryResults;
import io.milvus.param.dml.QueryParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class GraphSearchChannel implements SearchChannel {

    @Resource
    private MilvusClient milvusClient;

    @Resource
    private Neo4jKnowledgeGraphService neo4jService;

    @Resource
    private EntityRecognizer entityRecognizer;

    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String defaultCollectionName;

    @Value("${spring.ai.vectorstore.milvus.databaseName:my_xy}")
    private String databaseName;

    // 社区总结分数参数
    private static final double COMMUNITY_BASE_SCORE = 0.85;
    private static final double COMMUNITY_SCORE_INCREMENT = 0.05;
    private static final int COMMUNITY_MAX_MATCH = 3;

    // 原始Chunk分数参数
    private static final double CHUNK_BASE_SCORE = 0.80;
    private static final double CHUNK_SCORE_INCREMENT = 0.05;
    private static final int CHUNK_MAX_MATCH = 4;

    // 数量限制
    private static final int MAX_COMMUNITY_SUMMARIES = 3;
    private static final int MAX_CHUNKS_PER_COMMUNITY = 5;
    private static final int MAX_TOTAL_CHUNKS = 20;

    // 最高分数
    private static final double MAX_SCORE = 0.98;

    private static final SearchChannelResult emptyResult = SearchChannelResult.builder()
            .channelName("Graph-search")
            .chunks(Collections.emptyList())
            .metadata(Collections.emptyList())
            .build();

    @Override
    public String getName() {
        return "Graph-search";
    }

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public String getType() {
        return "Graph-search";
    }

    @Override
    public boolean isEnabled(SearchContext context) {
        String query = context.getOriginalQuery();
        if (query == null || query.isBlank()) return false;
        List<String> entities = entityRecognizer.extractEntities(query);
        return !entities.isEmpty();
    }

    @Override
    @RagTraceNode(name = "实体社区图召回", type = "search")
    public SearchChannelResult search(SearchContext context) {
        String query = context.getOriginalQuery();
        List<String> entities = entityRecognizer.extractEntities(query);
        if (entities.isEmpty()) {
            return emptyResult;
        }

        // 1. 调用优化后的服务，直接获取 CommunitySearchResult DTO
        CommunitySearchResult communityResult = neo4jService.getCommunitySummariesAndChunks(
                entities, MAX_COMMUNITY_SUMMARIES, MAX_CHUNKS_PER_COMMUNITY);

        List<Map.Entry<String, Integer>> communitySummaryEntries = communityResult.getSummaries();
        Set<String> allChunkIds = communityResult.getChunkIds();

        // 2. 降级：如果没有预计算社区总结，则动态生成临时总结
        if (communitySummaryEntries.isEmpty()) {
            log.info("实体未找到预计算社区，降级为查询时动态生成临时总结");
            communitySummaryEntries = neo4jService.buildTemporaryCommunitySummaries(entities);
        }

        // 3. 【已移除】不再需要单独补充独立实体，因为 step1 已通过 UNION 一并获取

        // 4. 从 Milvus 获取原始 Chunk 内容
        List<RetrievedChunk> originalChunks = fetchChunksFromMilvus(allChunkIds);

        // 5. 为原始 Chunk 计算分数（基于匹配实体数）
        Map<String, Integer> chunkMatchCountMap = neo4jService.getChunkMatchCountMap(entities);
        for (RetrievedChunk chunk : originalChunks) {
            String docId = chunk.getId();
            if (docId == null || docId.isBlank()) {
                Object v = chunk.getMetadata() == null ? null : chunk.getMetadata().get("doc_id");
                docId = v == null ? null : String.valueOf(v);
            }
            int matchCount = chunkMatchCountMap.getOrDefault(docId, 0);
            double score = Math.min(CHUNK_BASE_SCORE +
                    Math.min(matchCount, CHUNK_MAX_MATCH) * CHUNK_SCORE_INCREMENT, MAX_SCORE);
            chunk.setScore(score);
        }

        // 6. 将社区总结包装为高分 Chunk
        List<RetrievedChunk> summaryChunks = communitySummaryEntries.stream()
                .map(entry -> {
                    String summary = entry.getKey();
                    int matchCount = entry.getValue();
                    double score = Math.min(COMMUNITY_BASE_SCORE +
                            Math.min(matchCount, COMMUNITY_MAX_MATCH) * COMMUNITY_SCORE_INCREMENT, MAX_SCORE);
                    return RetrievedChunk.builder()
                            .content(summary)
                            .score(score)
                            .metadata(Map.of("type", "community_summary"))
                            .build();
                })
                .toList();

        // 7. 合并、排序、截断
        List<RetrievedChunk> combined = new ArrayList<>(summaryChunks);
        combined.addAll(originalChunks);
        combined.sort(Comparator.comparingDouble(RetrievedChunk::getScore).reversed());
        if (combined.size() > MAX_TOTAL_CHUNKS) {
            combined = combined.subList(0, MAX_TOTAL_CHUNKS);
        }

        log.info("GraphSearch(Local) 召回: 总结 {} 条, 原始片段 {} 条, 最终返回 {} 条",
                summaryChunks.size(), originalChunks.size(), combined.size());

        return SearchChannelResult.builder()
                .channelName(getName())
                .chunks(combined)
                .metadata(combined.stream().map(RetrievedChunk::getMetadata).collect(Collectors.toList()))
                .build();
    }

    /**
     * 从 Milvus 批量获取原始 Chunk 内容，不设置分数
     */
    private List<RetrievedChunk> fetchChunksFromMilvus(Set<String> docIds) {
        if (docIds == null || docIds.isEmpty()) return Collections.emptyList();

        String expr = "doc_id in [" +
                docIds.stream().map(id -> "'" + id + "'").collect(Collectors.joining(",")) + "]";

        QueryResults results = milvusClient.query(QueryParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(defaultCollectionName)
                .withExpr(expr)
                .withOutFields(Arrays.asList("doc_id", "content", "metadata"))
                .build()).getData();

        List<String> returnedDocIds = null;
        List<String> contentList = null;
        List<String> metadataJsonList = null;

        for (FieldData field : results.getFieldsDataList()) {
            switch (field.getFieldName()) {
                case "doc_id" -> returnedDocIds = field.getScalars().getStringData().getDataList();
                case "content" -> contentList = field.getScalars().getStringData().getDataList();
                case "metadata" -> {
                    List<ByteString> bsList = field.getScalars().getJsonData().getDataList();
                    metadataJsonList = bsList.stream()
                            .map(ByteString::toStringUtf8)
                            .collect(Collectors.toList());
                }
            }
        }

        if (returnedDocIds == null || contentList == null) return Collections.emptyList();
        int size = contentList.size();
        if (metadataJsonList == null) metadataJsonList = Collections.nCopies(size, "{}");

        List<RetrievedChunk> chunks = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            Map<String, Object> metadata = parseMetadata(metadataJsonList.get(i));
            metadata.put("doc_id", returnedDocIds.get(i));   // 保留 doc_id 用于后续匹配
            chunks.add(RetrievedChunk.builder()
                    .id(returnedDocIds.get(i))
                    .content(contentList.get(i))
                    .score(0.0)   // 外部重设
                    .metadata(metadata)
                    .build());
        }
        return chunks;
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
}