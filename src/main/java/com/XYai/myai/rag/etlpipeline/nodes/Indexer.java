package com.XYai.myai.rag.etlpipeline.nodes;

import com.XYai.myai.mapper.FileRecordMapper;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.rag.etlpipeline.pojo.NodeConfig;
import com.XYai.myai.rag.etlpipeline.pojo.NodeResult;
import com.XYai.myai.rag.etlpipeline.pojo.UploadProperties;
import com.XYai.myai.rag.graph.Neo4jKnowledgeGraphService;
import com.XYai.myai.rag.milvus.MilvusAclManager;
import com.XYai.myai.rag.milvus.MilvusMetadataFilter;
import com.XYai.myai.rag.milvus.pojo.FileRecord;
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

    @Resource
    private FileRecordMapper fileRecordMapper;

    @Resource
    private Neo4jKnowledgeGraphService neo4jService;

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

            try {
                batchInsertWithParallelEmbedding(chunks);
            } catch (Exception e) {
                log.info("插入Milvus失败 docId:{}", context.getDocument().getId());
                return NodeResult.fail("插入Milvus失败");
            }
            // 文件记录
            saveFileRecord(chunks);
            // 权限写入
            milvusAclManager.addFileUserACl(chunks, collectionName);
            CompletableFuture.runAsync(() -> neo4jService.triggerCommunityMaintenanceIfNeeded(), executor);
            log.info("向量入库成功 → 集合名: {}, 分块数量: {}", collectionName, chunks.size());
            return NodeResult.ok("向量入库完成，集合名=" + collectionName + "，分块数量=" + chunks.size());
        } catch (Exception e) {
            log.error("向量入库失败 → 集合名: {}", collectionName, e);
            return NodeResult.fail("向量入库失败：" + e.getMessage());
        }
    }

    private void saveFileRecord(List<Document> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        // 1. 提取去重后的文件ID
        Map<String, String> distinctFiles = new LinkedHashMap<>();
        for (Document chunk : chunks) {
            String chunkId = chunk.getId();
            if (!StringUtils.hasText(chunkId)) continue;
            if (!distinctFiles.containsKey(chunkId)) {
                Object fileNameObj = chunk.getMetadata().get(IngestionContext.META_FILE_NAME);
                String fileName = fileNameObj != null ? fileNameObj.toString() : "";
                distinctFiles.put(chunkId, fileName);
            }
        }

        if (distinctFiles.isEmpty()) {
            return;
        }

        // 3. 构建待插入记录
        List<FileRecord> toInsert = new ArrayList<>();
        for (Map.Entry<String, String> entry : distinctFiles.entrySet()) {
            FileRecord record = new FileRecord();
            record.setFileChunkId(entry.getKey());
            record.setFileName(entry.getValue());
            record.setFileUsingCount(0L);
            toInsert.add(record);
        }

        // 4. 批量插入
        try {
            fileRecordMapper.insert(toInsert);
            log.info("新增文件记录 {} 条", toInsert.size());
        } catch (Exception e) {
            log.error("批量插入文件记录失败，受影响条数：{}", toInsert.size(), e);
        }
    }

    private void batchInsertWithParallelEmbedding(List<Document> chunks) {
        try {
            List<CompletableFuture<Map<String, Object>>> futures = chunks.stream()
                    .map(chunk -> CompletableFuture.supplyAsync(() -> {
                        try {
                            return processChunk(chunk);
                        } catch (Exception e) {
                            log.error("chunk {} embedding 失败，已跳过", chunk.getId(), e);
                            return null;
                        }
                    }, executor))
                    .toList();

            List<Map<String, Object>> insertDataList = futures.stream()
                    .map(CompletableFuture::join)
                    .toList();

            List<String> docIds = new ArrayList<>();
            List<String> contents = new ArrayList<>();
            List<List<Float>> contextEmbeddings = new ArrayList<>();
            List<List<Float>> questionEmbeddings = new ArrayList<>();
            List<JsonObject> metadataList = new ArrayList<>();

            for (Map<String, Object> data : insertDataList) {
                docIds.add((String) data.get("docId"));
                contents.add((String) data.get("content"));

                float[] contextVector = (float[]) data.get("embedding_context");
                float[] questionVector = (float[]) data.get("embedding_question");
                List<Float> cVec = new ArrayList<>(contextVector.length);
                List<Float> qVec = new ArrayList<>(contextVector.length);
                for (float v : contextVector) {
                    cVec.add(v);
                }
                for (float v : questionVector) {
                    qVec.add(v);
                }
                contextEmbeddings.add(cVec);
                questionEmbeddings.add(qVec);
                metadataList.add((JsonObject) data.get("metadata"));
            }

            List<InsertParam.Field> fields = Arrays.asList(
                    new InsertParam.Field("doc_id", docIds),
                    new InsertParam.Field("content", contents),
                    new InsertParam.Field("embedding_context", contextEmbeddings),
                    new InsertParam.Field("embedding_question", questionEmbeddings),
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
        Object questionObj = chunk.getMetadata().get("hypothetical_questions");
        String question = questionObj.toString();
        if (question == null || question.isBlank()) {
            question = originalText;
        }
        log.info("indexer processChunk 生成的问题: {}", question);


        // 2. 用问题文本和原文生成向量
        float[] questionVector = null;
        if (question != null) {
            questionVector = embeddingModel.embed(question);
        }
        float[] contextVector = null;
        if (originalText != null) {
            contextVector = embeddingModel.embed(originalText);
        }

        // 3. 对 metadata 进行白名单
        Map<String, Object> rawMeta = new HashMap<>(chunk.getMetadata());
        rawMeta.remove("hypothetical_questions");
        rawMeta.remove("knowledge_triples");
        Map<String, Object> filteredMeta = milvusMetadataFilter.filter(rawMeta);

        // 4. 将过滤后的 metadata 转换为 Gson JsonObject
        JsonObject metadataJson = new JsonObject();
        filteredMeta.forEach((k, v) -> {
            switch (v) {
                case String s -> metadataJson.addProperty(k, s);
                case Number number -> metadataJson.addProperty(k, number);
                case Boolean b -> metadataJson.addProperty(k, b);
                default -> metadataJson.addProperty(k, v.toString());
            }
        });

        // 5. 组装返回数据
        Map<String, Object> result = new HashMap<>();
        result.put("docId", chunk.getId());
        result.put("content", originalText);
        result.put("embedding_context", contextVector);
        result.put("embedding_question", questionVector);
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