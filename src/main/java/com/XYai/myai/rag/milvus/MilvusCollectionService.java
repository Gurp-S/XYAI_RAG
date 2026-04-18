package com.XYai.myai.rag.milvus;

import cn.hutool.core.util.StrUtil;
import com.XYai.myai.config.Result;
import com.XYai.myai.rag.milvus.POJO.MilvusCollections;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.*;
import io.milvus.param.index.CreateIndexParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.*;
import java.time.Instant;

import jakarta.annotation.Resource;
import org.redisson.api.RedissonClient;
import org.redisson.api.RBucket;
import com.XYai.myai.redis.RedisKeyConfig;
import java.util.concurrent.TimeUnit;
import org.redisson.api.RLock;

/**
 * Milvus 集合（Collection）管理服务
 * 功能：负责创建、删除、重建、查询 Milvus 集合，并通过 Redis 维护加载/存在状态
 * 用于 RAG 系统中的向量库多集合隔离管理（例如：不同业务/不同文件使用独立集合）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MilvusCollectionService {

    @Resource
    private MilvusAclManager milvusAclManager;
    // Milvus 默认分片数
    private static final int DEFAULT_SHARDS_NUM = 2;
    // 修改操作锁租期（秒）
    private static final long MODIFY_LOCK_LEASE_SECONDS = 30L;

    /**
     * Milvus 原生客户端
     */
    @Resource
    private final MilvusServiceClient milvusClient;

    /**
     * 向量嵌入模型（用于将文本转为向量）
     */
    @Resource
    private final EmbeddingModel embeddingModel;

    /**
     * 底层 Milvus 基础操作服务
     */
    @Resource
    private final MilvusService milvusService;


    /**
     * Redisson 分布式锁客户端
     */
    @Resource
    private RedissonClient redissonClient;

    /**
     * 默认集合名称（配置文件可覆盖）
     */
    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String defaultCollectionName;

    /**
     * 向量维度（根据嵌入模型配置）
     */
    @Value("${spring.ai.vectorstore.milvus.embeddingDimension:4096}")
    private int embeddingDimension;

    /**
     * 索引类型（如 IVF_FLAT、IVF_SQ8 等）
     */
    @Value("${spring.ai.vectorstore.milvus.index-type:ivf_flat}")
    private String indexType;

    /**
     * 向量相似度计算方式（COSINE / L2 / IP）
     */
    @Value("${spring.ai.vectorstore.milvus.metricType:COSINE}")
    private String metricType;

    /**
     * 索引聚类中心数量（nlist）
     */
    @Value("${spring.ai.vectorstore.milvus.index-params.nlist:1024}")
    private int indexNList;

    /**
     * Milvus 数据库名，默认 default
     */
    @Value("${spring.ai.vectorstore.milvus.databaseName:default}")
    private String databaseName;

    // ====================== 生命周期,添加创建,删除,重构 ======================

    /**
     * 向指定 Milvus 集合添加文档（自动向量化）
     *
     * @param collectionName 目标集合名
     * @param documents      文档列表（Spring AI Document 对象）
     * @return 执行结果
     */
    public Result<String> add(String collectionName, List<Document> documents) {
        // 空值判断
        if (documents == null || documents.isEmpty()) {
            return Result.error(400, "文档为空");
        }

        // 规范化集合名
        collectionName = resolveCollectionName(collectionName);

        // 确保集合存在并返回，将文档存入向量库
        VectorStore vectorStore = ensureReadyForWrite(collectionName);
        try {
            vectorStore.add(documents);
            //添加权限
            milvusAclManager.addFileUserACl(documents,collectionName);
            return Result.success("添加成功");
        } catch (Exception e) {
            log.error("向 Milvus 添加文档失败, collection={}, docsCount={}", collectionName, documents.size(), e);
            return Result.error(500, "向 Milvus 添加文档失败: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /**
     * 确保集合存在，如果不存在则自动创建
     * 用于写入操作前的准备
     *
     * @param collectionName 集合名称
     * @return 已准备好的 VectorStore
     */
    public VectorStore ensureReadyForWrite(String collectionName) {
        String resolved = resolveCollectionName(collectionName);
        // 存在直接返回
        if (StrUtil.equals(milvusService.exists(collectionName), MilvusCollections.EXISTS_HAVE_ACL)) {
            return getVectorStore(resolved);
        }

        // 显式创建 Milvus collection
        createCollectionIfAbsent(resolved);

        // 刷新缓存
        milvusService.refreshCache(resolved);
        // 找不到报错
        if (StrUtil.equals(milvusService.exists(collectionName), MilvusCollections.NO_EXISTS)) {
            throw new IllegalStateException("Milvus collection 创建失败: " + resolved);
        }
        return getVectorStore(resolved);
    }

    /**
     * 确保集合已显式加载后再用于检索。
     * 加载就可以用于查询,未加载返回NULL
     */
    public VectorStore ensureReadyForRead(String collectionName) {
        String resolved = resolveCollectionName(collectionName);
        if (StrUtil.equals(milvusService.exists(collectionName), MilvusCollections.NO_EXISTS)
                &&StrUtil.equals(milvusService.exists(collectionName), MilvusCollections.UNKNOWN_EXISTS)) {
            throw new IllegalStateException("Milvus collection 不存在: " + resolved);
        }

        if (isLoaded(resolved)) {
            return getVectorStore(resolved);
        }
        loadCollection(resolved);
        return getVectorStore(resolved);
    }

    /**
     * 删除 Milvus 集合
     *
     * @param collectionName 集合名
     * @return 删除结果
     */
    public Result<String> drop(String collectionName) {
        collectionName = resolveCollectionName(collectionName);
        RLock modifyLock = null;
        try {
            // 尝试获取分布式锁，防止并发操作
            modifyLock = tryAcquireModifyLock(collectionName, "drop");
            if (modifyLock == null) {
                return Result.error(409, "集合正在执行其他操作，请稍后重试: " + collectionName);
            }
            // 删除集合下所有的文件的该用户权限
            milvusAclManager.dropCollectionAcl(collectionName);

            // 调用 Milvus 客户端删除集合
            // TODO空集合需要再一定时间删除

            return Result.success("集合已删除：" + collectionName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error(500, "删除集合被中断");
        } catch (Exception e) {
            return Result.error(0, "删除集合失败：" + e.getMessage());
        } finally {
            // 释放锁
            releaseModifyLock(collectionName, modifyLock);
        }
    }

    /**
     * 重建集合 = 先删除 + 再创建
     * 注意：会清空该集合下所有向量数据
     *
     * @param collectionName 集合名
     * @return 重建结果
     */
    public Result<String> rebuild(String collectionName) {
        collectionName = resolveCollectionName(collectionName);
        RLock modifyLock = null;
        try {
            modifyLock = tryAcquireModifyLock(collectionName, "rebuild");
            if (modifyLock == null) {
                return Result.error(409, "集合正在执行其他操作，请稍后重试: " + collectionName);
            }
            // 删除旧集合
            Result<String> dropResult = drop(collectionName);
            if (dropResult.getCode() == null || dropResult.getCode() != 200) {
                return Result.error(0, "重建集合失败：删除旧集合失败 -> " + collectionName);
            }
            // 重新创建
            createCollectionIfAbsent(collectionName);
            if (StrUtil.equals(milvusService.exists(collectionName), MilvusCollections.NO_EXISTS)) {
                return Result.error(0, "重建集合失败：创建后仍不存在 -> " + collectionName);
            }
            return Result.success("集合重建成功：" + collectionName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.error(500, "重建集合被中断");
        } catch (Exception e) {
            return Result.error(0, "重建集合失败：" + e.getMessage());
        } finally {
            releaseModifyLock(collectionName, modifyLock);
        }
    }

    // ====================== 创建和获取 ======================

    /**
     * 规范集合名
     * 只保留字母、数字、下划线，其他字符替换为下划线
     *
     * @param collectionName 集合名
     * @return 规范名
     */
    public String resolveCollectionName(String collectionName) {
        String resolved;
        if (StringUtils.hasText(collectionName)) {
            resolved = collectionName.trim();
        } else {
            resolved = StringUtils.hasText(defaultCollectionName) ? defaultCollectionName.trim() : "my_ai";
        }

        // 将任意不被 Milvus 接受的字符替换为下划线，保证集合名只包含字母、数字和下划线
        String sanitized = resolved.replaceAll("[^0-9A-Za-z_]", "_");
        if (!sanitized.equals(resolved)) {
            log.warn("resolveCollectionName: sanitize '{}' -> '{}'", resolved, sanitized);
        }
        return sanitized.isBlank() ? "my_ai" : sanitized;
    }

    /**
     * 获取 VectorStore 实例。
     * 不再维护本地缓存，统一按需创建，并用 Redis 记录 presence 状态。
     */
    public VectorStore getVectorStore(String collectionName) {
        String resolved = resolveCollectionName(collectionName);
        return createVectorStore(resolved);
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
     * 字段固定：doc_id、content、embedding、metadata
     * 注意:存在后用户权限没有的话应该增加权限
     */
    @SuppressWarnings("deprecation")
    public void createCollectionIfAbsent(String collectionName) {
        String resolved = resolveCollectionName(collectionName);
        String existsAndAcl = milvusService.exists(resolved);
        //权限状态,不存在,存在有权限,存在无权限
        boolean collectionStatus = !StrUtil.equals(existsAndAcl, MilvusCollections.NO_EXISTS);
        //存在没有权限
        if (collectionStatus) {
            // 存在无权限增加权限
            if(StrUtil.equals(existsAndAcl, MilvusCollections.EXISTS_NO_ACL)){
                milvusAclManager.addUserCollectionsAcl(Collections.singletonList(collectionName));
            }
            //有返回
            return;
        }
        // 不存在
        RLock modifyLock = null;
        try {
            modifyLock = tryAcquireModifyLock(resolved, "create");
            if (modifyLock == null) {
                return;
            }
            // double-check，避免并发下重复建表
            if (!StrUtil.equals(existsAndAcl, MilvusCollections.NO_EXISTS)&&!StrUtil.equals(existsAndAcl, MilvusCollections.UNKNOWN_EXISTS)) {
                return;
            }
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

            // 构建创建集合参数
            CreateCollectionParam createCollectionParam = CreateCollectionParam.newBuilder()
                    .withDatabaseName(databaseName)
                    .withCollectionName(resolved)
                    .withShardsNum(DEFAULT_SHARDS_NUM)
                    .withFieldTypes(Arrays.asList(docIdField, contentField, embeddingField, metadataField))
                    .build();

            // 创建集合
            milvusClient.createCollection(createCollectionParam);
            // 创建向量索引
            createEmbeddingIndexIfAbsent(resolved);
            // 添加权限
            milvusAclManager.saveUserCollectionsAcl(Collections.singletonList(collectionName));
            // 加载集合到内存
            loadCollection(resolved);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Milvus collection 显式创建被中断: " + resolved, e);
        } catch (Exception e) {
            throw new IllegalStateException("Milvus collection 显式创建失败: " + resolved, e);
        } finally {
            releaseModifyLock(resolved, modifyLock);
        }
    }

    /**
     * 显式加载 collection，确保 Attu / 搜索侧能够立即看到并使用数据。
     */
    public void loadCollection(String collectionName) {
        //TODO取消用户AI可读文件
        String resolved = resolveCollectionName(collectionName);
        try {
            RLock modifyLock = tryAcquireModifyLock(resolved, "load");
            if (modifyLock == null) {
                return;
            }
            try {
                milvusClient.loadCollection(
                        LoadCollectionParam.newBuilder()
                                .withDatabaseName(databaseName)
                                .withCollectionName(resolved)
                                .build());
                //添加load权限
                milvusAclManager.userCollectionLoadAcl();
            } finally {
                releaseModifyLock(resolved, modifyLock);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("加载集合被中断: " + resolved, e);
        }
    }

    /**
     * 显式卸载 collection，释放内存中的已加载集合。
     */
    public void unloadCollection(String collectionName) throws Exception {
        String resolved = resolveCollectionName(collectionName);
        RLock modifyLock = null;
        try {
            modifyLock = tryAcquireModifyLock(resolved, "unload");
            if (modifyLock == null) {
                throw new Exception("集合正在执行其他操作，请稍后重试");
            }
            // 释放集合（卸载）
            milvusClient.releaseCollection(
                    ReleaseCollectionParam.newBuilder()
                            .withDatabaseName(databaseName)
                            .withCollectionName(resolved)
                            .build());
            // 不再维护单独的 milvusLoaded/milvusStorePresence 键；刷新缓存以更新 userCollectionsKey
            milvusService.refreshCache(resolved);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Exception("取消加载被中断");
        } catch (Exception e) {
            throw new Exception("取消加载失败");
        } finally {
            releaseModifyLock(resolved, modifyLock);
        }
    }

    /**
     * 轻量等待态查询：前端可轮询该接口判断集合是否有写操作正在进行。
     */
    public Map<String, Object> getModifyWaitState(String collectionName) {
        String resolved = resolveCollectionName(collectionName);
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("collectionName", resolved);
        // 查询是否被锁定
        boolean locked = false;
        try {
            locked = redissonClient.getLock(RedisKeyConfig.milvusModifyLockKey(resolved)).isLocked();
        } catch (Exception ignored) {
        }
        state.put("locked", locked);

        // 查询操作元信息
        try {
            RBucket<String> bucket = redissonClient.getBucket(RedisKeyConfig.milvusModifyWaitKey(resolved));
            String waitMetaJson = bucket.get();
            Map<String, Object> waitMeta = StrUtil.isBlank(waitMetaJson)
                    ? Collections.emptyMap()
                    : JSON.parseObject(waitMetaJson, new TypeReference<Map<String, Object>>() {});
            state.put("waitMeta", waitMeta == null ? Collections.emptyMap() : waitMeta);
        } catch (Exception ignored) {
            state.put("waitMeta", Collections.emptyMap());
        }
        return state;
    }

    /**
     * 非阻塞尝试获取集合修改操作的分布式锁（上传层负责等待/重试）。
     * 若当前被占用则立即返回 null。
     */
    private RLock tryAcquireModifyLock(String collectionName, String operation) throws InterruptedException {
        // 获取锁对象
        RLock lock = redissonClient.getLock(RedisKeyConfig.milvusModifyLockKey(collectionName));
        long leaseMillis = MODIFY_LOCK_LEASE_SECONDS * 1000L;
        boolean locked = lock.tryLock(0L, leaseMillis, TimeUnit.MILLISECONDS);
        if (!locked) {
            return null;
        }

        // 记录操作信息到 Redis，用于前端查看状态
        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("op", operation);
            meta.put("startedAt", Instant.now().toString());
            meta.put("ttlSec", MODIFY_LOCK_LEASE_SECONDS);

            var bucket = redissonClient.<String>getBucket(RedisKeyConfig.milvusModifyWaitKey(collectionName));
            bucket.set(JSON.toJSONString(meta));

            // 正确新版写法：使用 Duration，不再弃用
            bucket.expire(Duration.ofMillis(leaseMillis));

        } catch (Exception ignored) {
        }
        return lock;
    }

    /**
     * 释放修改锁
     */
    private void releaseModifyLock(String collectionName, RLock lock) {
        if (lock == null) {
            return;
        }
        try {
            // 删除等待状态
            redissonClient.getBucket(RedisKeyConfig.milvusModifyWaitKey(collectionName)).delete();
        } catch (Exception ignored) {
        }
        try {
            lock.unlock();
        } catch (Exception ignored) {
        }
    }

    /**
     * 判断集合是否已加载到内存
     */
    public boolean isLoaded(String collectionName) {
        String resolved = resolveCollectionName(collectionName);
        String existsAndAcl = milvusService.exists(resolved);
        // 集合存在，返回缓存
        if (!StrUtil.equals(existsAndAcl, MilvusCollections.NO_EXISTS)) {
            //集合加载缓存
            return StrUtil.equals(existsAndAcl, MilvusCollections.EXISTS_HAVE_ACL);
        }
        //
        try {
            var response = milvusClient.getLoadState(
                    GetLoadStateParam.newBuilder()
                            .withDatabaseName(databaseName)
                            .withCollectionName(resolved)
                            .build());

            if (response == null || response.getData() == null) {
                return false;
            }
            // 判断是否已加载
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
                        .build());
    }

    /**
     * 为向量字段创建索引
     */
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
            // 索引已存在则忽略
            if (message.contains("exist")) {
                return;
            }
            throw new IllegalStateException("Milvus collection 索引创建失败: " + collectionName, e);
        }
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

}