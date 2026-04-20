package com.XYai.myai.rag.milvus;

import cn.hutool.core.util.StrUtil;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.POJO.User;
import com.google.gson.JsonObject;
import io.milvus.param.dml.InsertParam;
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
import java.util.stream.LongStream;
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
        User user = LoginUserInfoManager.get();
        Long userId = user.getId();

        // 用户的权限集合：格式 "fileId:bitMap(chunkId)"
        RSet<String> userFileSet = redissonClient.getSet(RedisKeyConfig.userFileIdsKey(userId));
        // 集合的chunk列表：格式 "fileId:chunkSize"
        RSet<String> collectionFileSet = redissonClient.getSet(RedisKeyConfig.collectionFileIds(collectionName));
        // 1. 构建用户权限映射：Map<fileId, Set<String> 允许的chunkId>
        Map<String, Set<String>> userPermissionMap = userFileSet.stream()
                .filter(str -> str != null && str.contains(":"))
                .map(str -> str.split(":", 2))
                .collect(Collectors.toMap(
                        arr -> arr[0],
                        arr -> new HashSet<>(Arrays.asList(arr[1].split(",")))
                ));
        // 2. 过滤集合中的chunk
        return collectionFileSet.stream()
                .filter(str -> str != null && str.contains(":"))
                .filter(str -> {
                    String[] parts = str.split(":");
                    String fileId = parts[0];
                    String chunkId = parts[1];
                    // 检查是否存在
                    return userPermissionMap.containsKey(fileId)
                            && userPermissionMap.get(fileId).contains(chunkId);
                })
                .toList();
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
        // 获取用户权限集合（格式：fileId:totalChunk:）
        RSet<String> userFileSet = redissonClient.getSet(RedisKeyConfig.userFileIdsKey(userId));
        // 用 contains + 前缀匹配
        return userFileSet.stream()
                .filter(Objects::nonNull)
                .anyMatch(entry -> {
                    int colonIndex = entry.indexOf(':');
                    if (colonIndex < 0) return false;
                    String entryFileId = entry.substring(0, colonIndex);
                    return fileId.equals(entryFileId);
                });
    }


    /**
     * 删除该集合下所有文件当前用户的权限
     *
     * @param collectionName 要删除权限的集合名
     */
    public void deleteCollectionDocumentAcl(String collectionName) {
        Long userId = LoginUserInfoManager.get().getId();
        String redisUserFileIdKey = RedisKeyConfig.userFileIdsKey(userId);
        String redisCollectionFileKey = RedisKeyConfig.collectionFileIds(collectionName);
        RSet<String> collectionFileIds = redissonClient.getSet(redisCollectionFileKey);
        RSet<String> userFileIds = redissonClient.getSet(redisUserFileIdKey);
        // 1. 提取集合中的 fileId 列表
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
        // 2. 找出用户权限中属于该集合的条目
        Set<String> toRemove = userFileIds.stream()
                .filter(Objects::nonNull)
                .filter(entry -> {
                    int colonIndex = entry.indexOf(':');
                    if (colonIndex < 0) return false;
                    String userFileId = entry.substring(0, colonIndex);
                    return fileIdsInCollection.contains(userFileId);
                })
                .collect(Collectors.toSet());
        // 3. 批量删除
        if (!toRemove.isEmpty()) {
            userFileIds.removeAll(toRemove);
            log.info("删除集合[{}]权限成功，清理用户文件权限数量：{}", collectionName, toRemove.size());
        } else {
            log.info("删除集合[{}]，无需要清理的用户权限", collectionName);
        }
    }


    /**
     * 删除 Redis 里的集合下文件权限缓存
     */
    public void deleteDocumentAcl(Long chunkId, String fileId, String collectionName) {
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
        // 1. 获取用户权限集合
        RSet<String> userFileSet = redissonClient.getSet(RedisKeyConfig.userFileIdsKey(userId));
        // 2. 获取集合权限集合
        RSet<String> collectionFileSet = redissonClient.getSet(RedisKeyConfig.collectionFileIds(collectionName));
        collectionFileSet.removeIf(entry -> {
            if (entry == null || !entry.contains(":")) return false;
            String currentFileId = entry.split(":")[0];
            return fileId.equals(currentFileId);
        });
        userFileSet.stream()
                .filter(Objects::nonNull)
                .filter(entry -> entry.startsWith(fileId + ":"))
                .findFirst()
                .ifPresent(entry -> {
                    try {
                        // 拆分格式：fileId:totalChunk:removeChunkIds
                        String[] parts = entry.split(":", 3);
                        String fileIdPart = parts[0];
                        String totalChunkPart = parts[1];
                        String removePart = parts.length > 2 ? parts[2] : "";
                        // 把当前 chunkId 加入 remove 列表
                        Set<String> removeSet = new HashSet<>();
                        if (!removePart.isBlank()) {
                            removeSet.addAll(Arrays.asList(removePart.split(",")));
                        }
                        removeSet.add(chunkId.toString()); // 加入要删除的chunk
                        String newRemove = String.join(",", removeSet);
                        // 构建新的权限字符串
                        String newEntry = fileIdPart + ":" + totalChunkPart + ":" + newRemove;
                        // 替换 Redis 中的权限
                        userFileSet.remove(entry);
                        userFileSet.add(newEntry);
                        log.info("删除chunk权限成功 → fileId:{}, chunkId:{}, 新remove列表:{}",
                                fileId, chunkId, newRemove);
                    } catch (Exception e) {
                        log.error("更新chunk权限失败", e);
                    }
                });
    }

    public void addFileUserACl(String fileId, String collectionName, Long chunkSize) {
        Long userId = LoginUserInfoManager.get().getId();
        // 生成所有 chunkId: chunkSize/64
        String chunkIds = LongStream.range(0, chunkSize)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(","));
        // fileId:totalChunk:
        String userFileEntry = fileId + ":" + chunkSize + ":" + chunkIds;
        redissonClient.getSet(RedisKeyConfig.userFileIdsKey(userId)).add(userFileEntry);
        // collection 侧只存 fileId:totalChunk（用于元数据，不含具体 chunkIds）
        String collectionFileEntry = fileId + ":" + chunkSize;
        redissonClient.getSet(RedisKeyConfig.collectionFileIds(collectionName)).add(collectionFileEntry);
    }

    public void addFileUserACl(List<Document> documents, String collectionName) {
        if (documents == null || documents.isEmpty()) return;
        Long userId = LoginUserInfoManager.get().getId();
        Document firstDoc = documents.getFirst();
        // 拿 fileId 和 chunkSize
        String fileId = firstDoc.getMetadata().get("fileId").toString();
        String chunkSizeStr = firstDoc.getMetadata().get("chunkSize").toString();
        long chunkSize = Long.parseLong(chunkSizeStr);
        // 生成 chunkIds: 0,1,2,...,documents.size()-1
        String chunkIds = IntStream.range(0, documents.size())
                .mapToObj(String::valueOf)
                .collect(Collectors.joining(","));
        String userFileEntry = fileId + ":" + chunkSize + ":" + chunkIds;
        redissonClient.getSet(RedisKeyConfig.userFileIdsKey(userId)).add(userFileEntry);
        // collection 侧存 fileId:totalChunk
        String collectionFileEntry = fileId + ":" + chunkSize;
        redissonClient.getSet(RedisKeyConfig.collectionFileIds(collectionName)).add(collectionFileEntry);
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
            log.warn("moveCollectionToUnloaded: no current user");
            return false;
        }
        if (collectionName == null || collectionName.isBlank()) {
            log.warn("moveCollectionToUnloaded: collectionName is null or blank");
            return false;
        }
        Long userId = user.getId();
        String loadKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        String unloadKey = RedisKeyConfig.userUnloadCollectionsKey(userId);
        try {
            Boolean moved = stringRedisTemplate.opsForSet().move(loadKey, collectionName, unloadKey);

            if (Boolean.TRUE.equals(moved)) {
                log.info("moveCollectionToUnloaded: moved from load to unload: userId={}, collection={}", userId, collectionName);
                return true;
            }

            // 幂等处理：若 unload 中已有则视为成功；否则加入 unload
            Boolean existsInUnload = stringRedisTemplate.opsForSet().isMember(unloadKey, collectionName);
            if (Boolean.TRUE.equals(existsInUnload)) {
                return true;
            }
            stringRedisTemplate.opsForSet().add(unloadKey, collectionName);
            log.info("moveCollectionToUnloaded: added to unload set: userId={}, collection={}", userId, collectionName);
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
