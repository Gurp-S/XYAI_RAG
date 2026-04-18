package com.XYai.myai.rag.milvus;

import cn.hutool.core.util.StrUtil;
import com.XYai.myai.rag.milvus.POJO.MilvusCollections;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.MutationResult;
import io.milvus.param.R;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.ShowCollectionsParam;
import io.milvus.param.dml.DeleteParam;
import io.milvus.response.QueryResultsWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

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
    @Resource
    private MilvusAclManager milvusAclManager;
    @Value("${spring.ai.vectorstore.milvus.databaseName:default}")
    private String databaseName;
    private volatile Instant lastRefresh = Instant.EPOCH;
    @Autowired
    public MilvusService(MilvusServiceClient milvusClient) {
        this.milvusClient = milvusClient;
    }

    /**
     * 解析结果
     *
     * @param metadataResult
     * @return
     */
    @NotNull
    private static List<Map<String, Object>> getMetadataResultByMilvusClient(MilvusAclManager.metadataResult metadataResult) {
        QueryResultsWrapper wrapper = new QueryResultsWrapper(metadataResult.response().getData());
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
    }

    /**
     * 获取当前用户可见的 Milvus Collection 列表
     */
    public List<String> getAllCollectionNames() {
        // 先读取当前用户可见集合列表
        List<String> visibleCollections = milvusAclManager.getUserCollectionsAcl();
        if (visibleCollections != null && !visibleCollections.isEmpty()) {
            return visibleCollections;
        }
        // 双重检查 + 加锁刷新
        if (refreshLock.tryLock()) {
            try {
                return refreshCache(null);
            } finally {
                refreshLock.unlock();
            }
            // 未获得锁或已被其他线程刷新，继续返回空列表以避免阻塞调用线程
        }
        // 若 TTL 未过期但 Redis 没有命中，也返回空列表
        return Collections.emptyList();
    }

    /**
     * 强制刷新缓存（同步调用）
     */
    public List<String> refreshCache(String collectionNames) {
        try {
            // 1. 获取当前用户有权限的集合元数据
            List<String> userCollectionNamesAcl = null;
            if (collectionNames == null) {
                List<String> allCollectionNames = milvusClient.showCollections(
                        ShowCollectionsParam.newBuilder()
                                .withDatabaseName(databaseName)
                                .build()
                ).getData().getCollectionNamesList();
                userCollectionNamesAcl = new ArrayList<>();
                for (String collectionName : allCollectionNames) {
                    List<Map<String, Object>> userCollectionNameMetadata = getUserCollectionNameMetadata(collectionName);
                    if (userCollectionNameMetadata != null && !userCollectionNameMetadata.isEmpty())
                        userCollectionNamesAcl.add(collectionName);
                }
            } else {
                List<Map<String, Object>> userCollectionNameMetadata = getUserCollectionNameMetadata(collectionNames);

                // 2. 从元数据中提取 collectionName 列表
                userCollectionNamesAcl = userCollectionNameMetadata.stream()
                        .map(meta -> (String) meta.get("collectionName"))
                        .collect(Collectors.toList());
            }

            // 3. 同步写入 Redisson 缓存（多实例共享）
            milvusAclManager.saveUserCollectionsAcl(userCollectionNamesAcl);

            // 4. 更新最后刷新时间
            lastRefresh = Instant.now();
            log.debug("刷新 Milvus collections 缓存: {} 条", userCollectionNamesAcl.size());

            // 5. 返回最终权限集合（避免递归调用 getAllCollectionNames -> refreshCache）
            if (userCollectionNamesAcl == null) {
                return Collections.emptyList();
            }
            return userCollectionNamesAcl;
        } catch (Exception e) {
            log.warn("刷新 Milvus collections 缓存失败，保留旧缓存: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 判断指定集合是否存在 + 权限校验
     *
     * @param collectionName 集合名称
     * @return NO_EXISTS(不存在) / EXISTS_NO_ACL(无权限) / EXISTS_HAVE_ACL(有权限)
     */
    public String exists(String collectionName) {
        // 1. 处理空值 & 安全化集合名
        String resolved = StrUtil.trim(collectionName);
        if (StrUtil.isBlank(resolved)) {
            log.info("空集合名");
            return "";
        }
        String sanitized = sanitizeCollectionName(resolved);

        // 2. 先从缓存取权限（最快）
        String aclStatus = milvusAclManager.getCollectionAcl(sanitized);

        log.info("集合{},状态{}", collectionName,aclStatus);

        // 有权限 → 直接返回存在，不查 Milvus
        if (MilvusCollections.EXISTS_HAVE_ACL.equals(aclStatus)) {
            return MilvusCollections.EXISTS_HAVE_ACL;
        }
        // 缓存没有->不存在,存在->无权限
        try {
            boolean collectionExists = milvusClient.hasCollection(
                    HasCollectionParam.newBuilder()
                            .withDatabaseName(databaseName)
                            .withCollectionName(sanitized)
                            .build()
            ).getData();
            log.info("集合状态真实{}", collectionExists);
            // 3.1 Milvus 不存在 → 返回真实不存在
            if (!collectionExists) {
                return MilvusCollections.NO_EXISTS;
            }
            // 3.2 Milvus 存在，但缓存没有 → 权限没有
            return MilvusCollections.EXISTS_NO_ACL;
        } catch (Exception e) {
            throw new IllegalStateException("Milvus 集合检查失败: " + sanitized, e);
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
            //  权限过滤查找
            MilvusAclManager.metadataResult metadataResult = milvusAclManager.getCollectionMetadata(collectionName);
            //  防止空指针
            if (metadataResult.response() == null || metadataResult.response().getStatus() != R.Status.Success.getCode() || metadataResult.response().getData() == null) {
                String message = metadataResult.response() == null ? "response is null" : metadataResult.response().getMessage();
                log.warn("查询 集合数据空或失败, collection={}, expr={}, reason={}", collectionName, metadataResult.expr(), message);
                return Collections.emptyList();
            }
            // 解析结果
            return getMetadataResultByMilvusClient(metadataResult);
        } catch (Exception e) {
            log.warn("查询 Milvus 异常, collection={}, reason={}", collectionName, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * 删除指定的文档/分块
     */
    public void deleteDocument(String collectionName, String fileId) {
        // 删除权限
        Long userAcls = milvusAclManager.deleteDocumentAcl(fileId);
        // 构建表达式：
        // metadata["chunkId"] == 123
        String safeFileId = fileId == null ? "" : fileId.replace("\\", "\\\\").replace("\"", "\\\"");
        String expr = String.format("metadata[\"fileId\"] == \"%s\"", fileId);

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
