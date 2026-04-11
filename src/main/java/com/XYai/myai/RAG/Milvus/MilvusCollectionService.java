package com.XYai.myai.RAG.Milvus;

import com.XYai.myai.Config.Result;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.collection.*;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Milvus 集合（Collection）管理服务
 * 功能：负责创建、删除、重建、查询 Milvus 集合，并缓存 VectorStore 实例
 * 用于 RAG 系统中的向量库多集合隔离管理（例如：不同业务/不同文件使用独立集合）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusCollectionService {

    private static final int DEFAULT_SHARDS_NUM = 2;

    /**
     * Milvus 原生客户端
     */
    private final MilvusServiceClient milvusClient;

    /**
     * 向量嵌入模型（用于将文本转为向量）
     */
    private final EmbeddingModel embeddingModel;

    /**
     * 底层 Milvus 基础操作服务
     */
    private final MilvusService milvusService;

    /**
     * VectorStore 实例缓存
     * key：collectionName
     * value：对应集合的 VectorStore 操作对象
     * 使用 ConcurrentHashMap 保证线程安全
     */
    private final ConcurrentMap<String, VectorStore> storeCache = new ConcurrentHashMap<>();

    /**
     * 已显式加载的集合缓存，避免重复 load
     */
    private final ConcurrentMap<String, Boolean> loadedCache = new ConcurrentHashMap<>();

    /**
     * 默认集合名称（配置文件可覆盖）
     */
    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String defaultCollectionName;

    @Value("${spring.ai.vectorstore.milvus.embeddingDimension:4096}")
    private int embeddingDimension;

    @Value("${spring.ai.vectorstore.milvus.index-type:ivf_flat}")
    private String indexType;

    @Value("${spring.ai.vectorstore.milvus.metricType:COSINE}")
    private String metricType;

    @Value("${spring.ai.vectorstore.milvus.index-params.nlist:1024}")
    private int indexNList;

    @Value("${spring.ai.vectorstore.milvus.databaseName:default}")
    private String databaseName;

    // ====================== 生命周期,添加创建,删除,重构 ======================
    /**
     * 向指定 Milvus 集合添加文档（自动向量化）
     * @param collectionName 目标集合名
     * @param documents 文档列表（Spring AI Document 对象）
     * @return 执行结果
     */
    public Result<String> add(String collectionName, List<Document> documents) {
        // 空值判断
        if (documents == null || documents.isEmpty()) {
            return Result.error(400, "文档为空");
        }
        collectionName = resolveCollectionName(collectionName);

        // 确保集合存在并返回，将文档存入向量库
        VectorStore vectorStore = ensureReadyForWrite(collectionName);
        vectorStore.add(documents);

        return Result.success("添加成功");
    }

    /**
     * 确保集合存在，如果不存在则自动创建
     *
     * @param collectionName 集合名称
     * @return 已准备好的 VectorStore
     */
    public VectorStore ensureReadyForWrite(String collectionName) {
        String resolved = resolveCollectionName(collectionName);
        if (milvusService.exists(resolved)) {
            return getVectorStore(resolved);
        }

        // 显式创建 Milvus collection
        createCollectionIfAbsent(resolved);

        // 刷新缓存
        milvusService.refreshCache();
        // 找不到报错
        if (!milvusService.exists(resolved)) {
            throw new IllegalStateException("Milvus collection 创建失败: " + resolved);
        }
        return getVectorStore(resolved);
    }

    /**
     * 确保集合已显式加载后再用于检索。
     * 仅在首次访问时执行 load，后续复用本地加载标记。
     */
    public VectorStore ensureReadyForRead(String collectionName) {
        String resolved = resolveCollectionName(collectionName);
        if (!milvusService.exists(resolved)) {
            throw new IllegalStateException("Milvus collection 不存在: " + resolved);
        }

        loadedCache.computeIfAbsent(resolved, key -> {
            loadCollection(key);
            return Boolean.TRUE;
        });
        return getVectorStore(resolved);
    }


    /**
     * 删除 Milvus 集合
     * @param collectionName 集合名
     * @return 删除结果
     */
    public Result<String> drop(String collectionName) {
        collectionName = resolveCollectionName(collectionName);
        try {
            // 调用 Milvus 客户端删除集合
            milvusClient.dropCollection(
                    DropCollectionParam.newBuilder()
                            .withDatabaseName(databaseName)
                            .withCollectionName(collectionName)
                            .build()
            );
            // 同步清除缓存
            storeCache.remove(collectionName);
            loadedCache.remove(collectionName);
            milvusService.refreshCache();
            return Result.success("集合已删除：" + collectionName);
        } catch (Exception e) {
            return Result.error(0, "删除集合失败：" + e.getMessage());
        }
    }

    /**
     * 重建集合 = 先删除 + 再创建
     * 注意：会清空该集合下所有向量数据
     * @param collectionName 集合名
     * @return 重建结果
     */
    public Result<String> rebuild(String collectionName) {
        collectionName = resolveCollectionName(collectionName);
        try {
            // 删除
            Result<String> dropResult = drop(collectionName);
            if (dropResult.getCode() == null || dropResult.getCode() != 200) {
                return Result.error(0, "重建集合失败：删除旧集合失败 -> " + collectionName);
            }
            // 显式创建
            createCollectionIfAbsent(collectionName);
            loadCollection(collectionName);
            milvusService.refreshCache();
            if (!milvusService.exists(collectionName)) {
                return Result.error(0, "重建集合失败：创建后仍不存在 -> " + collectionName);
            }
            return Result.success("集合重建成功：" + collectionName);
        } catch (Exception e) {
            return Result.error(0, "重建集合失败：" + e.getMessage());
        }
    }
    // ====================== 创建和获取 ======================
    /**
     * 规范集合名
     * @param collectionName 集合名
     * @return 规范名
     */
    public String resolveCollectionName(String collectionName) {
        if (StringUtils.hasText(collectionName)) {
            return collectionName.trim();
        }
        return StringUtils.hasText(defaultCollectionName) ? defaultCollectionName.trim() : "my_ai";
    }

    /**
     * 获取 VectorStore 实例（带缓存）
     * 避免重复创建，提升性能
     */
    public VectorStore getVectorStore(String collectionName) {
        String resolved = resolveCollectionName(collectionName);
        return storeCache.computeIfAbsent(resolved, this::createVectorStore);
    }

    /**
     * 创建 MilvusVectorStore 实例
     * 由 Spring AI 封装，自动处理：
     * 1. 连接 Milvus
     * 2. 创建集合（不存在时）
     * 3. 向量入库/检索
     */
    private VectorStore createVectorStore(String collectionName) {
        return MilvusVectorStore.builder(milvusClient, embeddingModel)
                .databaseName(databaseName)
                .collectionName(collectionName)
                .build();
    }

    // ====================== 显示创建加载和刷盘 ======================
    /**
     * 对齐 Spring AI 默认 Schema 的显式建表 milvus collection，并按当前项目配置创建索引。
     */
    @SuppressWarnings("deprecation")
    public void createCollectionIfAbsent(String collectionName) {
        if (milvusService.exists(collectionName)) {
            return;
        }

        try {
            // 1. 主键字段：必须叫 doc_id
            FieldType docIdField = FieldType.newBuilder()
                    .withName("doc_id")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(36)
                    .withPrimaryKey(true)
                    .withAutoID(false) // Spring AI 通常由客户端生成 UUID
                    .build();

            // 2. 文本内容字段：必须叫 content
            FieldType contentField = FieldType.newBuilder()
                    .withName("content")
                    .withDataType(DataType.VarChar)
                    .withMaxLength(65535)
                    .build();

            // 3. 向量字段：必须叫 embedding
            FieldType embeddingField = FieldType.newBuilder()
                    .withName("embedding")
                    .withDataType(DataType.FloatVector)
                    .withDimension(embeddingDimension)
                    .build();

            // 4. 元数据字段：必须叫 metadata
            FieldType metadataField = FieldType.newBuilder()
                    .withName("metadata")
                    .withDataType(DataType.JSON)
                    .build();

            CreateCollectionParam createCollectionParam = CreateCollectionParam.newBuilder()
                    .withDatabaseName(databaseName)
                    .withCollectionName(collectionName)
                    .withShardsNum(DEFAULT_SHARDS_NUM)
                    .withFieldTypes(Arrays.asList(docIdField, contentField, embeddingField, metadataField))
                    .build();

            milvusClient.createCollection(createCollectionParam);
            createEmbeddingIndexIfAbsent(collectionName);
            // 清空缓存防止使用旧缓存
            storeCache.remove(collectionName);
            loadCollection(collectionName);
        } catch (Exception e) {
            throw new IllegalStateException("Milvus collection 显式创建失败: " + collectionName, e);
        }
    }


    /**
     * 显式加载 collection，确保 Attu / 搜索侧能够立即看到并使用数据。
     */
    public void loadCollection(String collectionName) {
        String resolved = resolveCollectionName(collectionName);
        // 显式加载后统一同步本地缓存，避免各个调用点重复写缓存
        milvusClient.loadCollection(
                LoadCollectionParam.newBuilder()
                        .withDatabaseName(databaseName)
                        .withCollectionName(resolved)
                        .build()
        );
        loadedCache.put(resolved, Boolean.TRUE);
        storeCache.computeIfAbsent(resolved, this::createVectorStore);
        milvusService.refreshCache();
    }

    /**
     * 显式卸载 collection，释放内存中的已加载集合。
     */
    public void unloadCollection(String collectionName) throws Exception {
        String resolved = resolveCollectionName(collectionName);
        try {
            milvusClient.releaseCollection(
                    ReleaseCollectionParam.newBuilder()
                            .withDatabaseName(databaseName)
                            .withCollectionName(resolved)
                            .build()
            );
            loadedCache.remove(resolved);
            storeCache.remove(resolved);
            milvusService.refreshCache();
        } catch (Exception e) {
            throw  new Exception("取消加载失败");
        }
    }

    public boolean isLoaded(String collectionName) {
        String resolved = resolveCollectionName(collectionName);

        if (!milvusService.exists(resolved)) {
            return false;
        }

        if (loadedCache.containsKey(resolved)) {
            return Boolean.TRUE.equals(loadedCache.get(resolved));
        }

        try {
            var response = milvusClient.getLoadState(
                    GetLoadStateParam.newBuilder()
                            .withDatabaseName(databaseName)
                            .withCollectionName(resolved)
                            .build()
            );

            if (response == null || response.getData() == null) {
                return false;
            }
            // 这里按“Loaded”判断，具体枚举名以你 IDE 自动补全结果为准
            return response.getData().getState().toString().equalsIgnoreCase("LoadStateLoaded");

        } catch (Exception e) {
            throw new IllegalStateException("查询 Milvus collection 加载状态失败: " + resolved, e);
        }
    }


    /**
     * 显式刷盘，让新写入尽快对外可见。
     */
    public void flush(String collectionName) {
        String resolved = resolveCollectionName(collectionName);
        milvusClient.flush(
                FlushParam.newBuilder()
                        .addCollectionName(resolved)
                        .build()
        );
    }

    private void createEmbeddingIndexIfAbsent(String collectionName) {
        try {
            milvusClient.createIndex(CreateIndexParam.newBuilder()
                    .withDatabaseName(databaseName)
                    .withCollectionName(collectionName)
                    .withFieldName("embedding")
                    .withIndexType(IndexType.valueOf(normalizeEnumName(indexType)))
                    .withMetricType(MetricType.valueOf(normalizeEnumName(metricType)))
                    .withExtraParam("{\"nlist\":" + indexNList + "}")
                    .build());
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (message.contains("exist")) {
                return;
            }
            throw new IllegalStateException("Milvus collection 索引创建失败: " + collectionName, e);
        }
    }

    private String normalizeEnumName(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toUpperCase().replace('-', '_');
    }

}