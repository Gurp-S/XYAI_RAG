package com.XYai.myai.RAG.Milvus;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.ShowCollectionsResponse;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.ShowCollectionsParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Milvus 向量数据库基础服务类。
 * 提供集合 (Collection) 的生命周期管理、分区管理以及底层的向量检索封装。
 */
@Slf4j
@Service
public class MilvusService {

    private final MilvusServiceClient milvusClient;

    @Autowired
    public MilvusService(MilvusServiceClient milvusClient) {
        this.milvusClient = milvusClient;
    }

    @Value("${spring.ai.vectorstore.milvus.databaseName:default}")
    private String databaseName;

    // 缓存相关
    private volatile List<String> cachedCollectionNames = Collections.emptyList();
    private volatile Instant lastRefresh = Instant.EPOCH;
    private final ReentrantLock refreshLock = new ReentrantLock();

    // 缓存 TTL，单位毫秒。可按需调整（例如 5 秒或 10 秒）
    private final long ttlMillis = Duration.ofSeconds(10).toMillis();

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
     * @param collectionName 集合名称
     * @return true 存在 / false 不存在
     */
    public boolean exists(String collectionName) {
        String resolved = collectionName == null ? null : collectionName.trim();
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
}
