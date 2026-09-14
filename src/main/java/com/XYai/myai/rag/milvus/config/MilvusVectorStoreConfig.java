package com.XYai.myai.rag.milvus.config;

import io.milvus.client.MilvusServiceClient;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import jakarta.annotation.Resource;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.stereotype.Service;

@Service
public class MilvusVectorStoreConfig {

    @Resource
    private MilvusServiceClient milvusClient;
    @Resource
    private EmbeddingModel embeddingModel;

    // 意图节点专用的向量库
    public VectorStore getIntentVectorStore() {
        return MilvusVectorStore.builder(milvusClient,embeddingModel)
                .databaseName("my_xy")                     // 关键：指定数据库
                .collectionName("intent")                  // 关键：指定集合
                .metricType(MetricType.COSINE)             // 可选，默认 COSINE
                .indexType(IndexType.IVF_FLAT)// 可选，默认 IVF_FLAT
                .batchingStrategy(new TokenCountBatchingStrategy()) // 可选
                .build();
    }

}