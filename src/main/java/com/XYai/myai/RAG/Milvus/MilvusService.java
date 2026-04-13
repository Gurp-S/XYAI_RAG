package com.XYai.myai.RAG.Milvus;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.grpc.QueryResults;
import io.milvus.grpc.ShowCollectionsResponse;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.ShowCollectionsParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Milvus 向量数据库基础服务类。
 * 提供集合 (Collection) 的生命周期管理、分区管理以及底层的向量检索封装。
 */
@Slf4j
@Service
public class MilvusService {

    @Resource
    private final MilvusServiceClient milvusClient;
    private final ReentrantLock refreshLock = new ReentrantLock();
    // 缓存 TTL，单位毫秒。可按需调整（例如 5 秒或 10 秒）
    private final long ttlMillis = Duration.ofSeconds(10).toMillis();
    @Value("${spring.ai.vectorstore.milvus.databaseName:default}")
    private String databaseName;
    // 缓存相关
    private volatile List<String> cachedCollectionNames = Collections.emptyList();
    private volatile Instant lastRefresh = Instant.EPOCH;

    @Autowired
    public MilvusService(MilvusServiceClient milvusClient) {
        this.milvusClient = milvusClient;
    }

    /**
     * 获取 Milvus 中所有 Collection 的名称（带本地缓存，过期后会刷新）
     */
    public List<String> getAllCollectionNames() {
        Instant now = Instant.now();
        if (cachedCollectionNames == null || lastRefresh.plusMillis(ttlMillis).isBefore(now)) {
            // 双重检查 + 加锁刷新
            if (refreshLock.tryLock()) {
                try {
                    // 再次检查以防并发
                    if (lastRefresh.plusMillis(ttlMillis).isBefore(now) || cachedCollectionNames == null) {
                        return refreshCache();
                    }
                } finally {
                    refreshLock.unlock();
                }
            } // 如果未获得锁，继续使用旧缓存，避免阻塞
        }
        return cachedCollectionNames;
    }

    /**
     * 强制刷新缓存（同步调用）
     */
    public List<String> refreshCache() {
        try {
            R<ShowCollectionsResponse> response = milvusClient.showCollections(
                    ShowCollectionsParam.newBuilder().withDatabaseName(databaseName).build());

            if (response.getStatus() != R.Status.Success.getCode()) {
                log.warn("获取 Milvus Collection 列表失败: {} - {}, 保存旧缓存", response.getStatus(), response.getMessage());
                return cachedCollectionNames;
            }
            List<String> names = response.getData().getCollectionNamesList();
            cachedCollectionNames = List.copyOf(names);
            lastRefresh = Instant.now();
            log.debug("刷新 Milvus collections 缓存: {} entries", cachedCollectionNames.size());
            return cachedCollectionNames;
        } catch (Exception e) {
            log.warn("刷新 Milvus collections 缓存失败，保留旧缓存: {}", e.getMessage());
            return cachedCollectionNames;
        }
    }

    /**
     * 判断指定集合是否存在
     *
     * @param collectionName 集合名称
     * @return true 存在 / false 不存在
     */
    public boolean exists(String collectionName) {
        String resolved = collectionName == null ? null : collectionName.trim();
        if (resolved == null || resolved.isBlank()) {
            resolved = "my_ai";
        }
        try {
            return milvusClient.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withDatabaseName(databaseName)
                            .withCollectionName(resolved)
                            .build()
            ).getData();
        } catch (Exception e) {
            throw new IllegalStateException("Milvus collection 存在性检查失败: " + resolved, e);
        }
    }

    /**
     * 获取集合内的数据（模拟查看 metadata）
     *
     * @param collectionName 集合名
     * @return 每一行记录的 Map 列表
     */
    public List<Map<String, Object>> getCollectionNameMetadata(String collectionName) {
        if (collectionName == null || collectionName.trim().isEmpty()) {
            return Collections.emptyList();
        }


        try {
            // 1. 构建查询参数
            QueryParam queryParam = QueryParam.newBuilder()
                    .withDatabaseName(databaseName)
                    .withCollectionName(collectionName)
                    .withExpr("")
                    .withLimit(100L) // 必须加上 limit，否则空 expr 会报错
                    .withOutFields(Collections.singletonList("*")) // 返回所有字段
                    .build();

            // 2. 执行查询并检查响应状态
            R<QueryResults> response = milvusClient.query(queryParam);
            // 修复点 2: 严格检查响应状态，防止 NullPointerException
            if (response.getStatus() != R.Status.Success.getCode()) {
                log.error("查询 Milvus 失败: {}", response.getMessage());
                return Collections.emptyList();
            }

            if (response.getData() == null) {
                return Collections.emptyList();
            }

            // 3. 解析结果
            QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
            List<QueryResultsWrapper.RowRecord> rowRecords = wrapper.getRowRecords();

            List<Map<String, Object>> list = new ArrayList<>();
            for (QueryResultsWrapper.RowRecord rowRecord : rowRecords) {
                //取消googleJSON
                Map<String, Object> original = rowRecord.getFieldValues();
                Map<String, Object> cleanMap = new HashMap<>();
                original.forEach((key, value) -> {
                    if (value != null && value.getClass().getName().contains("google.gson")) {
                        cleanMap.put(key, value.toString());
                    } else {
                        cleanMap.put(key, value);
                    }
                });
                list.add(cleanMap);
            }
            return list;

        } catch (Exception e) {
            log.error("获取集合 {} 数据异常: ", collectionName, e);
            return Collections.emptyList();
        }
    }

    /**
     * 删除指定的文档/分块
     */
    public void deleteDocument(String collectionName, String kbId, String fileName, String chunkId) {
        // 构建表达式：metadata["kbId"] == "xxx" && metadata["fileName"] == "yyy" && metadata["chunkId"] == 123
        String safeKbId = kbId == null ? "" : kbId.replace("\\", "\\\\").replace("\"", "\\\"");
        String safeFileName = fileName == null ? "" : fileName.replace("\\", "\\\\").replace("\"", "\\\"");
        String expr = String.format(
                "metadata[\"kbId\"] == \"%s\" && metadata[\"fileName\"] == \"%s\" && metadata[\"chunkId\"] == %s",
                safeKbId, safeFileName, chunkId);

        DeleteParam deleteParam = DeleteParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(collectionName)
                .withExpr(expr)
                .build();
        R<MutationResult> response = milvusClient.delete(deleteParam);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new RuntimeException("删除 Milvus 数据失败: " + response.getMessage());
        }
    }

    /**
     * 模糊搜索集合
     * @param str 搜索词
     * @return 搜索结果
     */
    public List<String> search(String str) {
        //获取所有向量集合名字
        List<String> collectionNames = getAllCollectionNames();
        //判空
        if (collectionNames.isEmpty()) return null;

        String strLowerCase = str.toLowerCase();
        return collectionNames.stream()
                .filter(name -> name.toLowerCase().contains(strLowerCase))
                .sorted()
                .toList();
    }
}
