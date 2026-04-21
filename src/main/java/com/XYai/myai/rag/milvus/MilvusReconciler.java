package com.XYai.myai.rag.milvus;

import cn.hutool.core.util.StrUtil;
import com.XYai.myai.redis.RedisKeyConfig;
import io.milvus.client.MilvusClient;
import io.milvus.param.dml.DeleteParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBitSet;
import org.redisson.api.RSet;
import org.redisson.api.RBitSet;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 后台 Reconciler：异步清理 Redis 索引与 Milvus 元数据不一致的项，
 * 并对 Redis 中过期/丢失的 fileId 做轻量级回收。
 * 设计原则：非破坏性、幂等、低频执行。仅清理 Redis 层的垃圾引用，避免误删 Milvus 原始数据。
 */
@Slf4j
@Service
public class MilvusReconciler {

    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String physicalCollectionName;
    @Value("${spring.ai.vectorstore.milvus.databaseName:default}")
    private String databaseName;
    @Resource
    private MilvusClient milvusClient;
    @Resource
    private RedissonClient redissonClient;

    /**
     * 选择性删除
     *
     * @param type 删除的类型 fileChunk collection total
     */
    public void reconciler(String type) {
        // 根据类型选择方法
        if (StrUtil.equals(type, "total")) {
            reconcilerCollection("collection");
            reconcilerCollection("fileChunk");
        }
        if (StrUtil.equals(type, "collection")) {
            reconcilerCollection(type);
        }
        if (StrUtil.equals(type, "fileChunk")) {
            reconcilerFileChunk(type);
        }
    }

    /**
     * collection:fileIds user:collection fileId:collection
     *
     * @param type collection
     */
    private void reconcilerCollection(String type) {
        RSet<String> collectionSet = redissonClient.getSet(RedisKeyConfig.deletionMonitorByType(type));
        Set<String> toDeleteCollections = new HashSet<>(collectionSet.readAll());

        if (toDeleteCollections.isEmpty()) {
            log.info("reconcilerCollection: 没有待删除的 collection");
            return;
        }
        log.info("reconcilerCollection: 开始清理集合");

        // 所有用户的集合权限 和 文件集合映射
        String userLoadPattern = RedisKeyConfig.PREFIX + "user:collections:*";
        String userUnloadPattern = RedisKeyConfig.PREFIX + "user:collections:unloaded:*";
        String fileCollection = RedisKeyConfig.PREFIX + "file:hash:";

        // 用户有 -> 从删除列表移除
        processUserCollectionKeys(userLoadPattern, toDeleteCollections);
        processUserCollectionKeys(userUnloadPattern, toDeleteCollections);
        // 删除 fileId:collection
        processFileCollectionKeys(fileCollection, toDeleteCollections);
        // 删除最终无主集合
        for (String coll : toDeleteCollections) {
            if (StrUtil.isBlank(coll)) continue;
            // 删除 collection:fileIds
            String collFileKey = RedisKeyConfig.PREFIX + "collection:fileIds:" + coll;
            redissonClient.getKeys().delete(collFileKey);
            log.info("已删除集合元数据: {}", collFileKey);
        }

        // 清空已处理记录
        collectionSet.clear();
    }

    /**
     * user:fileId:chunk fileId:collection collection:fileIds
     *
     * @param type fileChunk
     */
    private void reconcilerFileChunk(String type) {
        // 进行了删除的文件 fileId:chunkId
        RSet<String> fileChunkSet = redissonClient.getSet(RedisKeyConfig.deletionMonitorByType(type));
        // 快照
        Set<String> toDeleteFiles = new HashSet<>(fileChunkSet.readAll());
        if (toDeleteFiles.isEmpty()) {
            log.info("reconcilerFileChunk: 没有待处理的 fileChunk");
            return;
        }

        // 拼接fileId:chunkId
        Set<String> toDeleteFilesChunk = toDeleteFiles;
        for (String key : toDeleteFiles) {
            String[] arr = key.split(":", 2);
            String fileId = arr[0];
            String chunkIdStr = arr[1];
            int chunkId = Integer.parseInt(chunkIdStr);
            // toDeleteFilesChunk 设置为在所有用户位图中都为 0 的 file:chunk , 位图全为 0 删除 key
            deleteUserFileChunk(fileId, chunkId);

            // 从 toDeleteFilesChunk 获取 fileId 减小 collection:fileIds 中 fileId:chunkSize 如果 fileId:chunkSize 为 0 删除 fileId:chunkSize 的key
            deleteCollectionFile(toDeleteFiles);

            // 删除 toDeleteFilesChunk 的 Milvus 文档 (metadata fileId == fileId AND chunkId == chunkId)
            deleteMilvusFileChunk(toDeleteFiles);
        }
        // 清空
        fileChunkSet.clear();
    }

    private void deleteMilvusFileChunk(Set<String> toDeleteFiles) {
    }

    private void deleteCollectionFile(Set<String> toDeleteFiles) {
    }

    private void deleteUserFileChunk(String fileId, int chunkId) {

    }

    private void processUserCollectionKeys(String keyPattern, Set<String> toDeleteCollections) {
        Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(keyPattern);
        for (String userKey : keys) {
            try {
                // 读取用户集合的当前成员
                RSet<String> userCollections = redissonClient.getSet(userKey);
                Set<String> userOwned = new HashSet<>(userCollections.readAll());
                // 从 toDeleteCollections 删除中有用户的 collection
                toDeleteCollections.removeAll(userOwned);
            } catch (Exception e) {
                log.warn("reconcilerCollection: 处理用户 key={} 时出错", userKey, e);
            }
        }
    }

    private void processFileCollectionKeys(String keyPattern, Set<String> toDeleteCollections) {
        Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(keyPattern);
        for (String fileKey : keys) {
            RSet<String> fileCollections = redissonClient.getSet(fileKey);
            Set<String> fileOwned = new HashSet<>(fileCollections.readAll());
            fileOwned.retainAll(toDeleteCollections);
            if (fileOwned.isEmpty()) continue;
            fileCollections.removeAll(fileOwned);
        }
    }
}