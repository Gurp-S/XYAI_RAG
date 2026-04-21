package com.XYai.myai.rag.milvus;

import cn.hutool.core.util.StrUtil;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.POJO.User;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBitSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
@Component
public class MilvusAclManager {

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

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

            // =====================
            // 1-based 遍历
            // =====================
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
        if (bitSet != null) {
            try {
                // =====================
                // 1-based：直接使用 chunkId，不 -1
                // =====================
                bitSet.set(chunkId, false);
            } catch (Exception e) {
                log.error("更新chunk权限失败", e);
            }
        }
    }

    // =============================
    // 【1-based】添加全量权限
    // =============================
    public void addFileUserACl(String fileId, String collectionName, Long chunkSize) {
        Long userId = LoginUserInfoManager.get().getId();
        int cap = (int) Math.min(chunkSize, RedisKeyConfig.MAX_CHUNK_PER_FILE);

        // =====================
        // 1-based：1 ~ cap
        // =====================
        List<Integer> all = IntStream.rangeClosed(1, cap)
                .boxed()
                .collect(Collectors.toList());

        setUserFileChunks(userId, fileId, all, chunkSize);
        redissonClient.getSet(RedisKeyConfig.collectionFileIds(collectionName)).add(fileId + ":" + chunkSize);
    }

    public void addFileUserACl(List<Document> documents, String collectionName) {
        if (documents == null || documents.isEmpty()) return;
        Long userId = LoginUserInfoManager.get().getId();
        Document firstDoc = documents.get(0);
        String fileId = firstDoc.getMetadata().get("fileId").toString();
        long chunkSize = Long.parseLong(firstDoc.getMetadata().get("chunkSize").toString());

        List<Integer> chunkIds = documents.stream()
                .map(doc -> doc.getMetadata().get("chunkId"))
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(Integer::parseInt)
                .toList();

        setUserFileChunks(userId, fileId, chunkIds, chunkSize);
        redissonClient.getSet(RedisKeyConfig.collectionFileIds(collectionName)).add(fileId + ":" + chunkSize);
    }

    /**
     * 设置用户对某文件的 chunk 权限位（1-based）
     */
    private void setUserFileChunks(Long userId, String fileId, Collection<Integer> chunkIds, long chunkSize) {
        RBitSet bitSet = redissonClient.getBitSet(RedisKeyConfig.userFileBitKey(userId, fileId));
        int cap = (int) Math.min(chunkSize, RedisKeyConfig.MAX_CHUNK_PER_FILE);
        if (cap <= 0) return;

        chunkIds.stream()
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .filter(id -> id >= 1 && id <= cap)
                .forEach(id -> bitSet.set(id, true));

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
        String loadKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        String unloadKey = RedisKeyConfig.userUnloadCollectionsKey(userId);
        try {
            redissonClient.getSet(loadKey).remove(collectionName);
            redissonClient.getSet(unloadKey).remove(collectionName);
        } catch (Exception e) {
            log.error("删除文件权限失败", e);
        }
    }
}