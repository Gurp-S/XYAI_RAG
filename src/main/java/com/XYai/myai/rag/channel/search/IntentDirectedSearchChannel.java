package com.XYai.myai.rag.channel.search;

import cn.hutool.core.collection.CollUtil;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchChannel;
import com.XYai.myai.rag.channel.pojo.SearchChannelResult;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.rag.intent.pojo.IntentNode;
import com.XYai.myai.rag.intent.pojo.SubQuestionIntent;
import com.XYai.myai.rag.milvus.MilvusFileManager;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import io.milvus.client.MilvusClient;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Component
public class IntentDirectedSearchChannel implements SearchChannel {
    //意图检索,收到意图树中的节点ID,例如root-chat-farewell
    //根据节点ID在向量数据库中映射直接检索

    @Resource
    private MilvusClient milvusClient;

    @Resource(name = "searchChannelExecutor")
    private ThreadPoolTaskExecutor searchChannelExecutor;
    @Resource
    private MilvusFileManager milvusFileManager;

    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String physicalCollectionName;
    @Value("${spring.ai.vectorstore.milvus.databaseName:my_xy}")
    private String databaseName;

    private static final int DEFAULT_TOP_K = 5;
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};

    @Override
    public String getName() {
        return "intent-directed-search";
    }

    @Override
    public int getPriority() {
        return 1;  // 优先级最高，优先执行
    }

    @Override
    public String getType() {
        return "intent-directed-search";
    }


    @Override
    public boolean isEnabled(SearchContext context) {
        // 启用条件：有明确的意图
        return false;
    }

    @Override
    @RagTraceNode(name = "召回", type = "search")
    public SearchChannelResult search(SearchContext context) {
        // 1.获取意图
        List<SubQuestionIntent> subQuestionIntents = context.getKbIntents();
        // 1.1并行搜索
        List<CompletableFuture<List<RetrievedChunk>>> futures = subQuestionIntents.stream()
                .map(questionIntent -> CompletableFuture.supplyAsync(
                        () -> getChunks(questionIntent),
                        searchChannelExecutor
                ))
                .toList();
        List<RetrievedChunk> intentSearchResult = futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .sorted((a, b) -> Double.compare(b.getScore(), a.getScore()))
                .limit(DEFAULT_TOP_K)
                .collect(Collectors.toList());
        // 构建返回
        return SearchChannelResult.builder()
                .channelName(getName())
                .chunks(intentSearchResult)
                .metadata(intentSearchResult.stream().map(RetrievedChunk::getMetadata).toList())
                .build();
    }

    private List<RetrievedChunk> getChunks(SubQuestionIntent questionIntent) {
        // 2.意图映射数据库字段
        List<RetrievedChunk> result = new ArrayList<>();
        Set<String> uniqueIntentIds = questionIntent.getSubIntent().stream()
                .map(IntentNode::getNodeId)
                .collect(Collectors.toSet());
        if (uniqueIntentIds.isEmpty()) return Collections.emptyList();
        // 3. 构建 IN 表达式并转义
        String intentList = uniqueIntentIds.stream()
                .map(id -> "\"" + id.replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(","));
        String expr = String.format("metadata[\"%s\"] in [%s]",
                IngestionContext.META_INTENT_NODE, intentList);
        R<QueryResults> query = milvusClient.query(QueryParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(physicalCollectionName)
                .withExpr(expr)
                .withOutFields(List.of("content", "metadata"))
                .withLimit(16384L)
                .build());
        List<Map<String, Object>> metadataResultByMilvusClient = milvusFileManager.getMetadataResultByMilvusClient(query);
        //content->text
        for (Map<String, Object> stringObjectMap : metadataResultByMilvusClient) {
            String content = stringObjectMap.get("content").toString();
            Object metadataObj = stringObjectMap.get("metadata");
            Map<String, Object> metadata;
            if (metadataObj instanceof Map) {
                // 已经是 Map
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) metadataObj;
                metadata = m;
            } else if (metadataObj instanceof String) {
                try {
                    metadata = JSON.parseObject((String) metadataObj, MAP_TYPE);
                } catch (Exception e) {
                    log.warn("metadata JSON 解析失败: {}", metadataObj, e);
                    metadata = new HashMap<>();  // 降级空 Map
                }
            } else {
                metadata = new HashMap<>();  // 兜底
            }

            RetrievedChunk chunk = RetrievedChunk.builder()
                    .content(content)
                    .metadata(metadata)
                    .score(1.0)
                    .build();
            result.add(chunk);
        }
        return result;
    }
}