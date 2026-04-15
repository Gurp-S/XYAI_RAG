package com.XYai.myai.rag.milvus;

import com.XYai.myai.rag.milvus.POJO.UserCollectionCache;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.POJO.User;
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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
    private final Map<Long, UserCollectionCache> userPermissionCache = new ConcurrentHashMap<>();

    private volatile Instant lastRefresh = Instant.EPOCH;

    @Autowired
    public MilvusService(MilvusServiceClient milvusClient) {
        this.milvusClient = milvusClient;
    }

    /**
     * 权限缓存
     * @param userId 当前用户ID
     * @return 当前用户对应的集合权限缓存对象
     */
    private UserCollectionCache getOrCreateUserCache(Long userId) {
        UserCollectionCache cache = userPermissionCache.get(userId);
        if (cache != null) {
            return cache;
        }

        UserCollectionCache newCache = UserCollectionCache.builder()
                .cachedCollectionNames(Collections.emptyList())
                .build();
        UserCollectionCache previous = userPermissionCache.putIfAbsent(userId, newCache);
        return previous == null ? newCache : previous;
    }

    /**
     * 获取用户ID并判断是否登录
     * @return  userId用户ID
     */
    private Long currentUserId() {
        User user = LoginUserInfoManager.get();
        if(user == null){
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return user.getId();
    }

    /**
     * 获取 Milvus 中所有 Collection 的名称（带本地缓存，过期后会刷新）
     */
    public List<String> getAllCollectionNames() {
        Long userId = currentUserId();
        UserCollectionCache cache = getOrCreateUserCache(userId);
        List<String> cachedCollectionNames = cache.getCachedCollectionNames();
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
        Long userId = currentUserId();
        UserCollectionCache cache = getOrCreateUserCache(userId);
        List<String> cachedCollectionNames = cache.getCachedCollectionNames();
        try {
            R<ShowCollectionsResponse> response = milvusClient.showCollections(
                    ShowCollectionsParam.newBuilder().withDatabaseName(databaseName).build());

            if (response.getStatus() != R.Status.Success.getCode()) {
                log.warn("获取 Milvus Collection 列表失败: {} - {}, 保存旧缓存", response.getStatus(), response.getMessage());
                return cachedCollectionNames;
            }
            cachedCollectionNames = response.getData().getCollectionNamesList();
            cache.setCachedCollectionNames(cachedCollectionNames);
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
        String sanitized = sanitizeCollectionName(resolved);
        try {
            return milvusClient.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withDatabaseName(databaseName)
                            .withCollectionName(sanitized)
                            .build())
                    .getData();
        } catch (Exception e) {
            throw new IllegalStateException("Milvus collection 存在性检查失败: " + sanitized, e);
        }
    }

    /**
     * 将任意集合名规范为 Milvus 可接受的格式：仅保留字母数字和下划线，其余字符替换为下划线。
     */
    private String sanitizeCollectionName(String name) {
        if (name == null)
            return "my_ai";
        String sanitized = name.replaceAll("[^0-9A-Za-z_]", "_");
        if (sanitized.isBlank())
            return "my_ai";
        if (!sanitized.equals(name)) {
            log.warn("Milvus collection 名称包含非法字符，已自动规范: '{}' -> '{}'", name, sanitized);
        }
        return sanitized;
    }

    /**
     * 获取集合内的数据（模拟查看 metadata）
     *
     * @param collectionName 集合名
     * @return 每一行记录的 Map 列表
     */
    public List<Map<String, Object>> getUserCollectionNameMetadata(String collectionName) {
        if (collectionName == null || collectionName.trim().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            Long userId = LoginUserInfoManager.get().getId();

            String expr = String.format(
                    "metadata[\"ownerId\"] == %d OR " +
                    "metadata[\"sharedWith\"] in [%d]",
                    userId, userId
            );
            // 1. 构建查询参数
            QueryParam queryParam = QueryParam.newBuilder()
                    .withDatabaseName(databaseName)
                    .withCollectionName(collectionName)
                    .withExpr(expr)
                    .withOutFields(Arrays.asList("doc_id", "content", "metadata"))
                    .build();

            // 2. 执行查询并检查响应状态
            R<QueryResults> response = milvusClient.query(queryParam);
            //  防止空指针
            if (response == null || response.getStatus() != R.Status.Success.getCode() || response.getData() == null) {
                String message = response == null ? "response is null" : response.getMessage();
                log.warn("查询 Milvus 失败, collection={}, expr={}, reason={}", collectionName, expr, message);
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
            log.warn("查询 Milvus 异常, collection={}, reason={}", collectionName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 删除指定的文档/分块
     */
    public void deleteDocument(String collectionName, String kbId, String fileName, String chunkId) {
        // 构建表达式：metadata["kbId"] == "xxx" && metadata["fileName"] == "yyy" &&
        // metadata["chunkId"] == 123
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
     *
     * @param str 搜索词
     * @return 搜索结果
     */
    public List<String> search(String str) {
        // 获取所有向量集合名字
        List<String> collectionNames = getAllCollectionNames();
        // 判空
        if (collectionNames.isEmpty())
            return null;

        String strLowerCase = str.toLowerCase();
        return collectionNames.stream()
                .filter(name -> name.toLowerCase().contains(strLowerCase))
                .sorted()
                .toList();
    }
}
