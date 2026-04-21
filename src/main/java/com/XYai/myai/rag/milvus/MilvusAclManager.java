package com.XYai.myai.rag.milvus;

import cn.hutool.core.util.StrUtil;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.POJO.User;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBitSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

@Slf4j
@Service
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
        // 加载的集合
        String loadRedisKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        // 未加载的集合
        String unloadRedisKey = RedisKeyConfig.userUnloadCollectionsKey(userId);
        // 集合名
        RSet<String> permissionLoadSet = redissonClient.getSet(loadRedisKey);
        RSet<String> permissionUnloadSet = redissonClient.getSet(unloadRedisKey);
        // 拼接返回
        return Stream.concat(
                permissionLoadSet.readAll().stream(),   // 已加载
                permissionUnloadSet.readAll().stream()  // 未加载
        ).collect(Collectors.toList());
    }


    /**
     * 获取当前权限集合元数据(文档id)
     *
     * @param collectionName 当前集合
     * @return 文件ID
     */
    public List<String> getCollectionMetadata(String collectionName) {
        Long userId = LoginUserInfoManager.get().getId();
        List<String> result = new ArrayList<>();
        // 集合下fileId
        RSet<String> fileIdSet = redissonClient.getSet(RedisKeyConfig.collectionFileIds(collectionName));
        // 用户和集合的交集
        for (String entry : fileIdSet) {
            if (entry == null || !entry.contains(":")) {
                continue;
            }
            String[] parts = entry.split(":", 2);
            String fileId = parts[0];
            int chunkSize = Integer.parseInt(parts[1]);
            // 限制单次查询的最大 chunk 数
            int maxChunkSize = Math.min(chunkSize, (int) RedisKeyConfig.MAX_CHUNK_PER_FILE);
            if (maxChunkSize <= 0) {
                continue;
            }
            // 用户下fileId:chunkId
            RBitSet bitSet = redissonClient.getBitSet(RedisKeyConfig.userFileBitKey(userId, fileId));
            // 用户无权限跳过
            if (!bitSet.isExists()) {
                log.info("getCollectionMetadata 用户文件无权限:{}", fileId);
                continue;
            }
            // 索引遍历
            log.info("getCollectionMetadata 用户文件分块权限数量:{}", maxChunkSize);

            for (int i = 1; i <= maxChunkSize; i++) {
                if (bitSet.get(i)) {
                    log.info("getCollectionMetadata 用户当前分块:{}",i);
                    result.add(fileId + ":" + i);
                }
            }
        }
        log.info("getCollectionMetadata 用户文件分块权限:{}", result);
        return result;
    }

    /**
     * 获取集合的权限
     *
     * @param collectionName 集合名
     * @return EXISTS_HAVE_ACL = 有权限 | EXISTS_NO_ACL = 无权限
     */
    public Boolean getCollectionAcl(String collectionName) {
        // 获取当前用户可见的集合列表
        List<String> permissionSet = getUserCollectionsAcl();

        // 判空
        if (permissionSet == null) {
            return false;//都没有 没有权限
        }

        // 判断是否包含
        return permissionSet.stream()
                .anyMatch(name -> StrUtil.equals(name, collectionName));
    }

    public Boolean getFileAcl(String fileId) {
        User user = LoginUserInfoManager.get();
        if (user == null) {
            log.warn("getFileAcl: 用户未登录");
            return false;
        }
        Long userId = user.getId();
        if (fileId == null || fileId.isBlank()) {
            log.info("文件名为空");
            return false;
        }
        // 使用 RBitSet 判断用户是否对该 file 有任意 chunk 权限
        String bitKey = RedisKeyConfig.userFileBitKey(userId, fileId);
        RBitSet bitSet = redissonClient.getBitSet(bitKey);
        try {
            if (bitSet == null) return false;
            byte[] arr = bitSet.toByteArray();
            return arr != null && arr.length > 0;
        } catch (Exception e) {
            log.warn("getFileAcl bitset error: fileId={}, userId={}", fileId, userId, e);
            return false;
        }
    }


    /**
     * 删除该集合下所有文件当前用户的权限
     *
     * @param collectionName 要删除权限的集合名
     */
    public void deleteCollectionDocumentAcl(String collectionName) {
        Long userId = LoginUserInfoManager.get().getId();
        String redisCollectionFileKey = RedisKeyConfig.collectionFileIds(collectionName);
        RSet<String> collectionFileIds = redissonClient.getSet(redisCollectionFileKey);
        // 提取集合中的 fileId 列表
        Set<String> fileIdsInCollection = collectionFileIds.stream()
                .filter(Objects::nonNull)
                .map(str -> str.split(":", 2))
                .filter(parts -> parts.length >= 1)
                .map(parts -> parts[0])
                .collect(Collectors.toSet());
        if (fileIdsInCollection.isEmpty()) {
            log.info("集合[{}]无文件，无需清理权限", collectionName);
            return;
        }
        // 对于每个 fileId 删除用户的 bitset
        int removed = 0;
        for (String fid : fileIdsInCollection) {
            try {
                RBitSet bitSet = redissonClient.getBitSet(RedisKeyConfig.userFileBitKey(userId, fid));
                if (bitSet != null) {
                    bitSet.delete();
                    removed++;
                }
            } catch (Exception e) {
                log.debug("删除用户文件位图失败 userId={}, fileId={}", userId, fid, e);
            }
        }
        log.info("删除集合[{}]权限成功，清理用户文件位图数量：{}", collectionName, removed);
    }


    /**
     * 删除 Redis 里的集合下文件权限缓存
     */
    public void deleteDocumentAcl(Long chunkId, String fileId) {
        User user = LoginUserInfoManager.get();
        if (user == null) {
            log.warn("用户未登录，无法删除文档权限");
            return;
        }
        if (fileId == null || fileId.isBlank() || chunkId == null) {
            log.warn("fileId 或 chunkId 不能为空，无法删除");
            return;
        }
        Long userId = user.getId();
        // 清除用户对应的 bit
        RBitSet bitSet = redissonClient.getBitSet(RedisKeyConfig.userFileBitKey(userId, fileId));
        if (bitSet != null) {
            try {
                bitSet.set(chunkId, false);
                log.info("删除chunk权限成功 → fileId:{}, chunkId:{}", fileId, chunkId);
            } catch (Exception e) {
                log.error("更新chunk权限失败", e);
            }
        }
    }

    public void addFileUserACl(String fileId, String collectionName, Long chunkSize) {
        Long userId = LoginUserInfoManager.get().getId();
        int cap = (int) Math.min(chunkSize, RedisKeyConfig.MAX_CHUNK_PER_FILE);
        List<Integer> all = IntStream.range(0, cap).boxed().collect(Collectors.toList());
        setUserFileChunks(userId, fileId, all, chunkSize);
        // collection 侧只存 fileId:totalChunk（用于元数据，不含具体 chunkIds）
        redissonClient.getSet(RedisKeyConfig.collectionFileIds(collectionName)).add(fileId + ":" + chunkSize);
    }

    public void addFileUserACl(List<Document> documents, String collectionName) {
        if (documents == null || documents.isEmpty()) return;
        Long userId = LoginUserInfoManager.get().getId();
        Document firstDoc = documents.getFirst();
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
     * 设置用户对某文件的 chunk 权限位（使用 RBitSet）。
     */
    private void setUserFileChunks(Long userId, String fileId, Collection<Integer> chunkIds, long chunkSize) {
        RBitSet bitSet = redissonClient.getBitSet(RedisKeyConfig.userFileBitKey(userId, fileId));
        int cap = (int) Math.min(chunkSize, RedisKeyConfig.MAX_CHUNK_PER_FILE);
        if (cap <= 0) log.info("setUserFileChunks 分块大小错误:{}", cap);
        // 设置权限位
        chunkIds.stream().filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .forEach(id -> {
                    if (id >= 1 && id <= cap) bitSet.set(id, true);
                });
        log.info("保存用户分块权: userId={} fileId={} total={} bitcount={}",
                userId, fileId, cap, bitSet.cardinality());
    }

    public boolean userCollectionLoadAcl(String collectionName) {
        User user = LoginUserInfoManager.get();
        if (user == null) {
            log.warn("用户未登录:userCollectionLoadAcl");
            return false;
        }
        if (collectionName == null || collectionName.isBlank()) {
            log.warn("集合名未空:userCollectionLoadAcl");
            return false;
        }

        Long userId = user.getId();
        String loadKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        RSet<String> loadSet = redissonClient.getSet(loadKey);
        try {
            return loadSet.contains(collectionName);
        } catch (Exception e) {
            log.error("userCollectionLoadAcl 错误: userId={}, collection={}", userId, collectionName, e);
            return false;
        }
    }

    /**
     * 原子地将 collection 从 unloaded 移到 loaded（使用 SMOVE），并提供幂等性回退
     */
    public boolean moveCollectionToLoaded(String collectionName) {
        User user = LoginUserInfoManager.get();
        if (user == null) {
            log.warn("用户未登录:moveCollectionToLoaded");
            return false;
        }
        if (collectionName == null || collectionName.isBlank()) {
            log.warn("集合名为空:moveCollectionToLoaded");
            return false;
        }
        Long userId = user.getId();
        String loadKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        String unloadKey = RedisKeyConfig.userUnloadCollectionsKey(userId);
        try {
            Boolean moved = stringRedisTemplate.opsForSet().move(unloadKey, collectionName, loadKey);

            if (Boolean.TRUE.equals(moved)) {
                log.info("状态更改成功: userId={}, collection={}", userId, collectionName);
                return true;
            }

            // 幂等处理：若 load 中已有则视为成功；否则加入 load
            Boolean existsInLoad = stringRedisTemplate.opsForSet().isMember(loadKey, collectionName);
            if (Boolean.TRUE.equals(existsInLoad)) {
                return true;
            }
            stringRedisTemplate.opsForSet().add(loadKey, collectionName);
            log.info("加载集合: userId={}, collection={}", userId, collectionName);
            return true;
        } catch (Exception e) {
            log.error("更改状态失败: userId={}, collection={}", userId, collectionName, e);
            return false;
        }
    }

    /**
     * 原子地将 collection 从 loaded 移到 unloaded（使用 SMOVE），并提供幂等性回退
     */
    public boolean moveCollectionToUnloaded(String collectionName) {
        User user = LoginUserInfoManager.get();
        if (user == null) {
            log.warn("moveCollectionToUnloaded: 用户未登录");
            return false;
        }
        if (collectionName == null || collectionName.isBlank()) {
            log.warn("moveCollectionToUnloaded: 集合不存在");
            return false;
        }
        Long userId = user.getId();
        String loadKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        String unloadKey = RedisKeyConfig.userUnloadCollectionsKey(userId);
        try {
            Boolean moved = stringRedisTemplate.opsForSet().move(loadKey, collectionName, unloadKey);

            if (Boolean.TRUE.equals(moved)) {
                return true;
            }

            // 幂等处理：若 unload 中已有则视为成功；否则加入 unload
            Boolean existsInUnload = stringRedisTemplate.opsForSet().isMember(unloadKey, collectionName);
            if (Boolean.TRUE.equals(existsInUnload)) {
                return true;
            }
            stringRedisTemplate.opsForSet().add(unloadKey, collectionName);
            return true;
        } catch (Exception e) {
            log.error("moveCollectionToUnloaded failed: userId={}, collection={}", userId, collectionName, e);
            return false;
        }
    }

    public void deleteCollectionAcl(String collectionName) {
        User user = LoginUserInfoManager.get();
        if (user == null) {
            log.warn("用户为空:deleteCollectionAcl");
            return;
        }
        Long userId = user.getId();
        String loadKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        String unloadKey = RedisKeyConfig.userUnloadCollectionsKey(userId);
        try {
            redissonClient.getSet(loadKey).remove(collectionName);
            redissonClient.getSet(unloadKey).remove(collectionName);
        } catch (Exception e) {
            log.error("删除文件权限失败: userId={}, collection={}", userId, collectionName, e);
        }
    }
}
