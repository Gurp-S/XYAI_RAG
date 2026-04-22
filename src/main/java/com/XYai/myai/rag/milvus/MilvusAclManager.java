package com.XYai.myai.rag.milvus;

import cn.hutool.core.util.StrUtil;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.POJO.User;
import io.milvus.client.MilvusClient;
import io.milvus.param.dml.DeleteParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBitSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

@Slf4j
@Component
public class MilvusAclManager {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private MilvusClient milvusClient;

    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String physicalCollectionName;

    @Value("${spring.ai.vectorstore.milvus.databaseName:default}")
    private String databaseName;

    /**
     * 获取当前用户可见的集合列表（跨实例共享，Redis 优先）。
     */
    public List<String> getUserCollectionsAcl() {
        Long userId = LoginUserInfoManager.get().getId();
        String loadRedisKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        String unloadRedisKey = RedisKeyConfig.userUnloadCollectionsKey(userId);
        RSet<String> permissionLoadSet = redissonClient.getSet(loadRedisKey);
        RSet<String> permissionUnloadSet = redissonClient.getSet(unloadRedisKey);
        return Stream.concat(
                permissionLoadSet.readAll().stream(),
                permissionUnloadSet.readAll().stream()
        ).collect(Collectors.toList());
    }

    /**
     * 获取当前权限集合元数据(文档id)
     */
    public List<String> getCollectionFiles(String collectionName) {
        Long userId = LoginUserInfoManager.get().getId();
        List<String> result = new ArrayList<>();
        RSet<String> fileIdSet = redissonClient.getSet(RedisKeyConfig.collectionFileIds(collectionName));

        for (String entry : fileIdSet) {
            if (entry == null || !entry.contains(":")) continue;
            String[] parts = entry.split(":", 2);
            String fileId = parts[0];
            int chunkSize = Integer.parseInt(parts[1]);
            int maxChunkSize = Math.min(chunkSize, (int) RedisKeyConfig.MAX_CHUNK_PER_FILE);
            if (maxChunkSize <= 0) continue;

            RBitSet bitSet = redissonClient.getBitSet(RedisKeyConfig.userFileBitKey(userId, fileId));
            if (!bitSet.isExists()) continue;

            for (int i = 1; i <= maxChunkSize; i++) {
                if (bitSet.get(i)) {
                    result.add(fileId + ":" + i);
                }
            }
        }
        return result;
    }

    /**
     * 获取集合的权限
     */
    public Boolean getCollectionAcl(String collectionName) {
        List<String> permissionSet = getUserCollectionsAcl();
        if (permissionSet == null) return false;
        return permissionSet.stream().anyMatch(name -> StrUtil.equals(name, collectionName));
    }

    public Boolean getFileAcl(String fileId) {
        User user = LoginUserInfoManager.get();
        if (user == null) return false;
        Long userId = user.getId();
        if (fileId == null || fileId.isBlank()) return false;

        RBitSet bitSet = redissonClient.getBitSet(RedisKeyConfig.userFileBitKey(userId, fileId));
        try {
            return bitSet != null && bitSet.cardinality() > 0;
        } catch (Exception e) {
            log.warn("getFileAcl bitset error", e);
            return false;
        }
    }

    /**
     * 删除该集合下所有文件当前用户的权限
     */
    public void deleteCollectionDocumentAcl(String collectionName) {
        Long userId = LoginUserInfoManager.get().getId();
        RSet<String> collectionFileIds = redissonClient.getSet(RedisKeyConfig.collectionFileIds(collectionName));

        Set<String> fileIdsInCollection = collectionFileIds.stream()
                .filter(Objects::nonNull)
                .map(str -> str.split(":", 2))
                .filter(parts -> parts.length >= 1)
                .map(parts -> parts[0])
                .collect(Collectors.toSet());

        for (String fid : fileIdsInCollection) {
            try {
                RBitSet bitSet = redissonClient.getBitSet(RedisKeyConfig.userFileBitKey(userId, fid));
                if (bitSet != null) bitSet.delete();
            } catch (Exception e) {
                log.debug("删除用户文件位图失败", e);
            }
        }
    }

    /**
     * 删除 Redis 里的集合下文件权限缓存
     */
    public void deleteDocumentAcl(Long chunkId, String fileId) {
        User user = LoginUserInfoManager.get();
        if (user == null || fileId == null || fileId.isBlank() || chunkId == null) return;
        Long userId = user.getId();

        RBitSet bitSet = redissonClient.getBitSet(RedisKeyConfig.userFileBitKey(userId, fileId));
        boolean hadAcl = false;
        if (bitSet != null) {
            try {
                hadAcl = bitSet.get(chunkId); // 读取原值（1-based）
            } catch (Exception ignored) {
            }
            try {
                if (hadAcl) {
                    bitSet.set(chunkId, false);
                    if (bitSet.cardinality() == 0) {
                        try {
                            bitSet.delete();
                        } catch (Exception ignore) {
                        }
                    }
                }
            } catch (Exception e) {
                log.error("更新chunk权限失败", e);
                return;
            }
        }

        // 原来确实有权限 TODO该用户只有一个集合拥有此文件分块才计数器 - 1
        if (hadAcl) {
            RAtomicLong cnt = redissonClient.getAtomicLong(RedisKeyConfig.fileChunkUserCountKey(fileId, chunkId));
            long fileChunkUserCount = 0;
            try {
                fileChunkUserCount = cnt.decrementAndGet();
                if (fileChunkUserCount <= 0) {
                    // 负数判断
                    if (fileChunkUserCount < 0) {
                        cnt.set(0);
                    }
                    // 当计数为0时执行删除动作
                    deleteNoAclFileChunk(fileId, chunkId);
                    // 删除计数器 key
                    try {
                        redissonClient.getKeys().delete(RedisKeyConfig.fileChunkUserCountKey(fileId, chunkId));
                    } catch (Exception ignore) {
                    }
                }
            } catch (Exception e) {
                log.error("更新 fileChunk 计数器失败 fileId={} chunkId={}", fileId, chunkId, e);
            }
        }
    }

    public void deleteNoAclFileChunk(String fileId, Long chunkId) {
        fileId = fileId.replace("\"", "\\\"").replace("'", "''");
        // 构建
        String expr = String.format(
                "(metadata[\"fileId\"] == \"%s\" AND metadata['chunkId'] == %s)",
                fileId, chunkId);
        milvusClient.delete(DeleteParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(physicalCollectionName)
                .withExpr(expr)
                .build());
    }

    public void addFileUserACl(String fileId, String collectionName, Long chunkSize) {
        Long userId = LoginUserInfoManager.get().getId();
        int cap = (int) Math.min(chunkSize, RedisKeyConfig.MAX_CHUNK_PER_FILE);

        List<Long> all = LongStream.rangeClosed(1, cap)
                .boxed()
                .collect(Collectors.toList());

        setUserFileChunks(userId, fileId, all, chunkSize);
        redissonClient.getSet(RedisKeyConfig.collectionFileIds(collectionName)).add(fileId + ":" + chunkSize);
    }

    public void addFileUserACl(List<Document> documents, String collectionName) {
        if (documents == null || documents.isEmpty()) return;
        Long userId = LoginUserInfoManager.get().getId();
        Document firstDoc = documents.getFirst();
        String fileId = firstDoc.getMetadata().get("fileId").toString();
        long chunkSize = Long.parseLong(firstDoc.getMetadata().get("chunkSize").toString());

        List<Long> chunkIds = documents.stream()
                .map(doc -> doc.getMetadata().get("chunkId"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(Long::parseLong)
                .toList();

        setUserFileChunks(userId, fileId, chunkIds, chunkSize);
        redissonClient.getSet(RedisKeyConfig.collectionFileIds(collectionName)).add(fileId + ":" + chunkSize);
    }

    /**
     * 设置用户对某文件的 chunk 权限位（1-based）
     */
    private void setUserFileChunks(Long userId, String fileId, Collection<Long> chunkIds, long chunkSize) {
        RBitSet bitSet = redissonClient.getBitSet(RedisKeyConfig.userFileBitKey(userId, fileId));
        int cap = (int) Math.min(chunkSize, RedisKeyConfig.MAX_CHUNK_PER_FILE);
        if (cap <= 0) return;

        chunkIds.stream()
                .filter(Objects::nonNull)
                .filter(id -> id >= 1 && id <= cap)
                .forEach(id -> {
                            if (!bitSet.get(id)) {
                                bitSet.set(id, true);
                                redissonClient.getAtomicLong(RedisKeyConfig.fileChunkUserCountKey(fileId, id)).incrementAndGet();
                            }
                        }
                );
        log.info("保存用户分块权: fileId={} bitcount={}", fileId, bitSet.cardinality());
    }

    public boolean userCollectionLoadAcl(String collectionName) {
        User user = LoginUserInfoManager.get();
        if (user == null || collectionName == null || collectionName.isBlank()) return false;
        Long userId = user.getId();
        RSet<String> loadSet = redissonClient.getSet(RedisKeyConfig.userLoadCollectionsKey(userId));
        try {
            return loadSet.contains(collectionName);
        } catch (Exception e) {
            log.error("userCollectionLoadAcl 错误", e);
            return false;
        }
    }

    /**
     * 原子地将 collection 从 unloaded 移到 loaded
     */
    public boolean moveCollectionToLoaded(String collectionName) {
        User user = LoginUserInfoManager.get();
        if (user == null || collectionName == null || collectionName.isBlank()) return false;
        Long userId = user.getId();
        String loadKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        String unloadKey = RedisKeyConfig.userUnloadCollectionsKey(userId);

        try {
            Boolean moved = stringRedisTemplate.opsForSet().move(unloadKey, collectionName, loadKey);
            if (Boolean.TRUE.equals(moved)) return true;

            Boolean exists = stringRedisTemplate.opsForSet().isMember(loadKey, collectionName);
            if (Boolean.TRUE.equals(exists)) return true;

            stringRedisTemplate.opsForSet().add(loadKey, collectionName);
            return true;
        } catch (Exception e) {
            log.error("moveCollectionToLoaded failed", e);
            return false;
        }
    }

    /**
     * 原子地将 collection 从 loaded 移到 unloaded
     */
    public boolean moveCollectionToUnloaded(String collectionName) {
        User user = LoginUserInfoManager.get();
        if (user == null || collectionName == null || collectionName.isBlank()) return false;
        Long userId = user.getId();
        String loadKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        String unloadKey = RedisKeyConfig.userUnloadCollectionsKey(userId);

        try {
            Boolean moved = stringRedisTemplate.opsForSet().move(loadKey, collectionName, unloadKey);
            if (Boolean.TRUE.equals(moved)) return true;

            Boolean exists = stringRedisTemplate.opsForSet().isMember(unloadKey, collectionName);
            if (Boolean.TRUE.equals(exists)) return true;

            stringRedisTemplate.opsForSet().add(unloadKey, collectionName);
            return true;
        } catch (Exception e) {
            log.error("moveCollectionToUnloaded failed", e);
            return false;
        }
    }

    public void deleteCollectionAcl(String collectionName) {
        User user = LoginUserInfoManager.get();
        if (user == null) return;
        Long userId = user.getId();
        try {
            // 全局用户计数器 - 减 1
            RAtomicLong collectionUserCount = redissonClient.getAtomicLong(RedisKeyConfig.collectionUserCountKey(collectionName));
            long newCount = collectionUserCount.addAndGet(-1);

            // 清理当前用户的 load/unload set 中该 collection
            try {
                RSet<Object> loadCollection = redissonClient.getSet(RedisKeyConfig.userLoadCollectionsKey(userId));
                RSet<Object> unLoadCollection = redissonClient.getSet(RedisKeyConfig.userUnloadCollectionsKey(userId));
                loadCollection.remove(collectionName);
                unLoadCollection.remove(collectionName);
            } catch (Exception ignore) {
            }

            // 如果全局计数为 0 清理 collection 相关的全局数据
            if (newCount <= 0) {
                try {
                    // 删除集合下文件索引及计数器
                    redissonClient.getKeys().delete(RedisKeyConfig.collectionFileIds(collectionName));
                    redissonClient.getKeys().delete(RedisKeyConfig.collectionUserCountKey(collectionName));
                } catch (Exception ex) {
                    log.warn("删除: {} 集合失败", collectionName, ex);
                }
            }
        } catch (Exception e) {
            log.error("删除集合失败", e);
        }
    }
}