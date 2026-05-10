package com.XYai.myai.rag.etlpipeline.nodes;

import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.rag.etlpipeline.pojo.NodeConfig;
import com.XYai.myai.rag.etlpipeline.pojo.NodeResult;
import com.XYai.myai.rag.etlpipeline.pojo.UploadProperties;
import com.XYai.myai.rag.milvus.MilvusAclManager;
import com.XYai.myai.rag.milvus.MilvusMetadataFilter;
import com.XYai.myai.rag.milvus.pojo.MilvusMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.dml.InsertParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Component
public class Indexer implements Ingestion {

    @Resource
    private UploadProperties uploadProperties;

    @Resource
    private MilvusAclManager milvusAclManager;

    @Resource
    private MilvusServiceClient milvusClient;

    @Resource
    private MilvusMetadataFilter milvusMetadataFilter;

    @Resource
    private EmbeddingModel embeddingModel;

    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String defaultCollectionName;

    @Value("${spring.ai.vectorstore.milvus.databaseName:my_xy}")
    private String databaseName;

    @Resource(name = "uploadExecutor")
    private ThreadPoolTaskExecutor executor;

    @Override
    public String getNodeType() {
        return "indexer";
    }

    @RagTraceNode(name = "执行入库", type = "上传管道")
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<Document> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.fail("无分块数据，跳过向量入库");
        }

        String collectionName = resolveCollectionName(context, chunks, config);

        if (!Boolean.TRUE.equals(uploadProperties.getRagEnabled())) {
            return NodeResult.ok("RAG功能已关闭，跳过向量入库，分块数量=" + chunks.size());
        }

        try {
            if (!Boolean.TRUE.equals(milvusAclManager.getCollectionAcl(collectionName))) {
                log.warn("当前用户无权限写入集合: {}", collectionName);
                return NodeResult.fail("当前用户无权限写入集合: " + collectionName);
            }

            // 注意：不再在此处调用 milvusMetadataFilter.filter(chunks)
            // 过滤逻辑移至 processChunk 内部，以确保 hypothetical_questions 不被误删

            try {
                batchInsertWithParallelEmbedding(chunks);
            } catch (Exception e) {
                throw new RuntimeException("插入Milvus失败", e);
            } finally {
                // 权限仍然基于原始 chunks 进行写入
                milvusAclManager.addFileUserACl(chunks, collectionName);
            }

            log.info("向量入库成功 → 集合名: {}, 分块数量: {}", collectionName, chunks.size());
            return NodeResult.ok("向量入库完成，集合名=" + collectionName + "，分块数量=" + chunks.size());
        } catch (Exception e) {
            log.error("向量入库失败 → 集合名: {}", collectionName, e);
            return NodeResult.fail("向量入库失败：" + e.getMessage());
        }
    }

    private void batchInsertWithParallelEmbedding(List<Document> chunks) {
        try {
            List<CompletableFuture<Map<String, Object>>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(() -> processChunk(chunk), executor))
                    .toList();

            List<Map<String, Object>> insertDataList = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            List<String> docIds = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            List<List<Float>> embeddings = new ArrayList<>();
            List<JsonObject> metadataList = new ArrayList<>();

            for (Map<String, Object> data : insertDataList) {
                docIds.add((String) data.get("docId"));
                contents.add((String) data.get("content"));

                float[] arr = (float[]) data.get("embedding");
                List<Float> vec = new ArrayList<>(arr.length);
                for (float v : arr) {
                    vec.add(v);
                }
                embeddings.add(vec);

                metadataList.add((JsonObject) data.get("metadata"));
            }

            List<InsertParam.Field> fields = Arrays.asList(
                    new InsertParam.Field("doc_id", docIds),
                    new InsertParam.Field("content", contents),
                    new InsertParam.Field("embedding", embeddings),
                    new InsertParam.Field("metadata", metadataList)
            );

            InsertParam insertParam = InsertParam.newBuilder()
                    .withDatabaseName(databaseName)
                    .withCollectionName(defaultCollectionName)
                    .withFields(fields)
                    .build();

            milvusClient.insert(insertParam);

        } catch (Exception e) {
            log.error("并行批量插入失败", e);
            throw new RuntimeException("批量入库异常：" + e.getMessage());
        }
    }

    private Map<String, Object> processChunk(Document chunk) {
        String originalText = chunk.getText();

        // 1. 从原始 metadata 中提取 hypothetical_questions（过滤前取值）
        String questions = (String) chunk.getMetadata().get("hypothetical_questions");
        if (questions == null || questions.isBlank()) {
            questions = originalText;   // 回退使用原文
        }
        log.info("indexer processChunk 生成的问题: {}", questions);

        // 2. 用问题文本生成向量（而非原文）
        float[] vectorArray = embeddingModel.embed(questions);

        // 3. 对 metadata 进行白名单过滤（保留业务字段，移除 hypothetical_questions 等）
        Map<String, Object> rawMeta = new HashMap<>(chunk.getMetadata());
        rawMeta.remove("hypothetical_questions");      // 不存入 Milvus
        rawMeta.remove("knowledge_triples");           // 不存入 Milvus
        Map<String, Object> filteredMeta = milvusMetadataFilter.filter(rawMeta);

        // 4. 将过滤后的 metadata 转换为 Gson JsonObject（Milvus 要求）
        JsonObject metadataJson = new JsonObject();
        filteredMeta.forEach((k, v) -> {
            if (v instanceof String) {
                metadataJson.addProperty(k, (String) v);
            } else if (v instanceof Number) {
                metadataJson.addProperty(k, (Number) v);
            } else if (v instanceof Boolean) {
                metadataJson.addProperty(k, (Boolean) v);
            } else {
                metadataJson.addProperty(k, v.toString());
            }
        });

        // 5. 组装返回数据
        Map<String, Object> result = new HashMap<>();
        result.put("docId", chunk.getId());
        result.put("content", originalText);
        result.put("embedding", vectorArray);
        result.put("metadata", metadataJson);
        return result;
    }

    // ====================== 以下方法保持不变 ======================
    private String resolveCollectionName(IngestionContext context, List<Document> chunks, NodeConfig config) {
        String collectionName = readCollectionNameFromSettings(config == null ? null : config.getSettings());
        if (StringUtils.hasText(collectionName)) return collectionName;

        Map<String, Object> documentMetadata = context == null || context.getDocument() == null
                ? Collections.emptyMap() : context.getDocument().getMetadata();
        collectionName = readCollectionNameFromMetadata(documentMetadata);
        if (StringUtils.hasText(collectionName)) return collectionName;

        if (chunks != null && !chunks.isEmpty()) {
            collectionName = readCollectionNameFromMetadata(chunks.getFirst().getMetadata());
        }
        return StringUtils.hasText(collectionName) ? collectionName : defaultCollectionName;
    }

    private String readCollectionNameFromSettings(JsonNode settings) {
        if (settings != null && settings.has("collectionName")) {
            JsonNode node = settings.get("collectionName");
            if (node != null && !node.isNull() && StringUtils.hasText(node.asText())) {
                return node.asText();
            }
        }
        return null;
    }

    private String readCollectionNameFromMetadata(Map<String, Object> metadata) {
        if (metadata == null || !metadata.containsKey(IngestionContext.META_COLLECTION_NAME)) return null;
        Object value = metadata.get(IngestionContext.META_COLLECTION_NAME);
        return value == null ? null : String.valueOf(value);
    }
}