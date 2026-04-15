package com.XYai.myai.rag.etlpipeline.Nodes;

import com.XYai.myai.rag.etlpipeline.POJO.IngestionContext;
import com.XYai.myai.rag.etlpipeline.POJO.NodeConfig;
import com.XYai.myai.rag.etlpipeline.POJO.NodeResult;
import com.XYai.myai.rag.etlpipeline.POJO.UploadProperties;
import com.XYai.myai.rag.milvus.MilvusCollectionService;
import com.XYai.myai.rag.milvus.MilvusMetadataFilter;
import com.fasterxml.jackson.databind.JsonNode;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 向量索引节点（ETL 流程最终节点）
 * 功能：将分块后的文档片段进行向量化，并批量写入向量数据库
 * 负责：获取分块 → 确定集合名 → 校验开关 → 执行入库 → 结果返回
 */
@Slf4j
@Component
public class Indexer implements Ingestion {

    // ====================== 配置注入 ======================

    /**
     * 上传配置（控制 RAG 功能是否启用）
     */
    @Resource
    private UploadProperties uploadProperties;

    /**
     * Milvus 集合管理工具（创建、判断、加载、写入）
     */
    @Resource
    private MilvusCollectionService milvusCollectionService;

    /**
     * Milvus 原始客户端，用于创建索引
     */
    @Resource
    private MilvusServiceClient milvusClient;

    /**
     * 过滤数据
     */
    @Resource
    private MilvusMetadataFilter milvusMetadataFilter;

    /**
     * 默认集合名（配置未指定时使用）
     */
    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String defaultCollectionName;

    /**
     * Milvus 数据库名
     */
    @Value("${spring.ai.vectorstore.milvus.databaseName:default}")
    private String databaseName;

    /**
     * 向量索引类型（如 IVF_FLAT、HNSW）
     */
    @Value("${spring.ai.vectorstore.milvus.index-type:ivf_flat}")
    private String indexType;

    /**
     * 向量相似度计算方式（如 COSINE、L2、IP）
     */
    @Value("${spring.ai.vectorstore.milvus.metricType:COSINE}")
    private String metricType;

    /**
     * 索引参数 nlist（聚类中心数量）
     */
    @Value("${spring.ai.vectorstore.milvus.index-params.nlist:1024}")
    private int indexNList;

    // ====================== 节点类型 ======================

    /**
     * 返回当前节点类型：indexer（索引/入库节点）
     */
    @Override
    public String getNodeType() {
        return "indexer";
    }

    // ====================== 核心执行逻辑 ======================

    /**
     * 执行向量入库核心逻辑
     * @param context 文档摄取上下文（包含分块数据、原始文档、元数据）
     * @param config 节点配置信息
     * @return 节点执行结果（成功/失败 + 描述信息）
     */
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // 1. 获取分块列表，判空保护
        List<Document> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.fail("无分块数据，跳过向量入库");
        }

        // 2. 按优先级解析出最终要使用的集合名称（collectionName）
        // 优先级：节点配置 > 文档元数据 > 分块元数据 > 默认值
        String collectionName = resolveCollectionName(context, chunks, config);

        // 3. 如果配置关闭了 RAG 功能，则直接跳过入库
        if (!Boolean.TRUE.equals(uploadProperties.getRagEnabled())) {
            return NodeResult.ok("RAG功能已关闭，跳过向量入库，分块数量=" + chunks.size());
        }

        try {
            // 确保集合存在并准备好可写入
            milvusCollectionService.ensureReadyForWrite(collectionName);

            //过滤metadata数据
            List<Document> filterChunks = milvusMetadataFilter.filter(chunks);

            // 为向量字段创建索引（不存在才创建）
            ensureEmbeddingIndex(collectionName);

            // 执行向量库批量写入
            milvusCollectionService.add(collectionName, filterChunks);

            // 强制刷盘，确保数据立即落盘
            milvusCollectionService.flush(collectionName);

            log.info("向量入库成功 → 集合名: {}, 分块数量: {}", collectionName, chunks.size());
            return NodeResult.ok("向量入库完成，集合名=" + collectionName + "，分块数量=" + chunks.size());
        } catch (Exception e) {
            // 5. 异常捕获：写入失败记录日志并返回失败信息
            log.error("向量入库失败 → 集合名: {}", collectionName, e);
            return NodeResult.fail("向量入库失败：" + e.getMessage());
        }
    }

    // ====================== 集合名称解析（优先级） ======================

    /**
     * 按优先级解析集合名称
     * 优先级：
     * 1. 节点配置（settings）
     * 2. 原始文档元数据
     * 3. 第一个分块元数据
     * 4. 默认配置
     *
     * @param context 摄取上下文
     * @param chunks 分块列表
     * @param config 节点配置
     * @return 最终有效的集合名
     */
    private String resolveCollectionName(IngestionContext context, List<Document> chunks, NodeConfig config) {
        // 优先级1：从节点配置中读取
        String collectionName = readCollectionNameFromSettings(config == null ? null : config.getSettings());
        if (StringUtils.hasText(collectionName)) {
            return collectionName;
        }

        // 优先级2：从原始文档的元数据中读取
        Map<String, Object> documentMetadata = context == null || context.getDocument() == null
                ? Collections.emptyMap()
                : context.getDocument().getMetadata();
        collectionName = readCollectionNameFromMetadata(documentMetadata);
        if (StringUtils.hasText(collectionName)) {
            return collectionName;
        }

        // 优先级3：从第一个分块的元数据中读取
        if (chunks != null && !chunks.isEmpty()) {
            Map<String, Object> chunkMetadata = chunks.getFirst().getMetadata();
            collectionName = readCollectionNameFromMetadata(chunkMetadata);
        }

        // 都没有则返回默认值
        return StringUtils.hasText(collectionName) ? collectionName : defaultCollectionName;
    }

    /**
     * 从节点配置（settings）中读取 collectionName
     */
    private String readCollectionNameFromSettings(JsonNode settings) {
        if (settings != null && settings.has("collectionName")) {
            JsonNode node = settings.get("collectionName");
            if (node != null && !node.isNull() && StringUtils.hasText(node.asText())) {
                return node.asText();
            }
        }
        return null;
    }

    /**
     * 从元数据（metadata）中读取 collectionName
     */
    private String readCollectionNameFromMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty() || !metadata.containsKey(IngestionContext.META_COLLECTION_NAME)) {
            return null;
        }
        Object value = metadata.get(IngestionContext.META_COLLECTION_NAME);
        return value == null ? null : String.valueOf(value);
    }

    // ====================== 索引创建 ======================

    /**
     * 构建 Milvus 向量索引参数
     * @param collectionName 集合名
     */
    private CreateIndexParam buildEmbeddingIndexParam(String collectionName) {
        String extraParam = "{\"nlist\":" + indexNList + "}";
        return CreateIndexParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(collectionName)
                .withFieldName("embedding")
                .withIndexType(parseIndexType(indexType))
                .withMetricType(parseMetricType(metricType))
                .withExtraParam(extraParam)
                .build();
    }

    /**
     * 字符串转 Milvus 索引类型枚举
     */
    private IndexType parseIndexType(String value) {
        return IndexType.valueOf(normalizeEnumName(value));
    }

    /**
     * 字符串转 Milvus 相似度类型枚举
     */
    private MetricType parseMetricType(String value) {
        return MetricType.valueOf(normalizeEnumName(value));
    }

    /**
     * 枚举名称规范化：转大写、横线转下划线
     */
    private String normalizeEnumName(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase().replace('-', '_');
    }

    /**
     * 确保向量索引存在（幂等：已存在则不创建）
     * @param collectionName 目标集合名
     */
    private void ensureEmbeddingIndex(String collectionName) {
        // 规范化集合名（防止空、特殊字符等）
        String resolved = milvusCollectionService.resolveCollectionName(collectionName);
        try {
            milvusClient.createIndex(buildEmbeddingIndexParam(resolved));
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            // 如果是索引已存在，直接忽略
            if (message.contains("exist")) {
                return;
            }
            // 其他异常直接抛出
            throw e;
        }
    }
}