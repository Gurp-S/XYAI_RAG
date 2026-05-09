package com.XYai.myai.xyAdmin;

import com.XYai.myai.config.Result;
import com.XYai.myai.mapper.UserMapper;
import com.XYai.myai.rag.milvus.MilvusAclManager;
import com.XYai.myai.rag.milvus.MilvusCollectionService;
import com.XYai.myai.rag.milvus.MilvusFileManager;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.user.pojo.User;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Milvus 向量库管理类
 * 提供后台管理：文件/集合/用户权限/数据处理
 */
@Slf4j
@Component
public class MilvusManager {

    @Resource
    private MilvusFileManager milvusFileManager;

    @Resource
    private MilvusAclManager milvusAclManager;

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private MilvusCollectionService milvusCollectionService;

    /**
     * 获取所有文件信息（从 Milvus + Redis 关联）
     */
    public Result<List<Map<String, Object>>> getAllFiles() {
        try {
            List<Map<String, Object>> resultList = new ArrayList<>();

            // 获取所有文件哈希相关的 Key
            Set<String> hashKeys = stringRedisTemplate.keys(RedisKeyConfig.fileHashKey("*"));
            if (hashKeys == null || hashKeys.isEmpty()) {
                return Result.success(resultList);
            }

            for (String hashKey : hashKeys) {
                String fileId = stringRedisTemplate.opsForValue().get(hashKey);
                if (fileId == null) continue;

                Map<String, Object> fileInfo = new HashMap<>();
                fileInfo.put("fileId", fileId);
                fileInfo.put("hashKey", hashKey);

                // 获取文件关联的知识库
                Set<String> collections = getCollectionsByFileId(fileId);
                fileInfo.put("collections", collections);

                // 文件分片使用计数
                String countKey = RedisKeyConfig.fileChunkUserCountKey(fileId, 0L);
                String count = stringRedisTemplate.opsForValue().get(countKey);
                fileInfo.put("useCount", count == null ? 0 : Long.parseLong(count));

                resultList.add(fileInfo);
            }

            log.info("获取所有文件成功，共 {} 个文件", resultList.size());
            return Result.success(resultList);

        } catch (Exception e) {
            log.error("getAllFiles失败", e);
            return Result.error(500, "获取文件列表失败：" + e.getMessage());
        }
    }

    /**
     * 根据文件ID获取关联的知识库
     */
    private Set<String> getCollectionsByFileId(String fileId) {
        Set<String> result = new HashSet<>();
        try {
            // 获取所有知识库
            Set<String> collectionKeys = stringRedisTemplate.keys(RedisKeyConfig.collectionFileIds("*"));
            if (collectionKeys != null) {
                for (String key : collectionKeys) {
                    Set<String> fileIds = stringRedisTemplate.opsForSet().members(key);
                    if (fileIds != null && fileIds.contains(fileId)) {
                        String collectionName = key.substring(key.lastIndexOf(":") + 1);
                        result.add(collectionName);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取文件关联知识库失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 获取所有知识库集合
     */
    public Result<Set<String>> getAllCollections() {
        try {
            Set<String> collections = new HashSet<>();
            Set<String> collectionKeys = stringRedisTemplate.keys(RedisKeyConfig.collectionFileIds("*"));

            if (collectionKeys != null) {
                for (String key : collectionKeys) {
                    String collectionName = key.substring(key.lastIndexOf(":") + 1);
                    collections.add(collectionName);
                }
            }

            log.info("获取所有知识库成功，共 {} 个", collections.size());
            return Result.success(collections);

        } catch (Exception e) {
            log.error("获取知识库列表失败", e);
            return Result.error(500, "获取知识库列表失败：" + e.getMessage());
        }
    }

    /**
     * 获取某个知识库的授权用户列表
     */
    public Result<List<User>> getCollectionUsers(String collectionName) {
        try {
            if (collectionName == null || collectionName.isBlank()) {
                return Result.error(400, "知识库名称不能为空");
            }

            // 获取所有用户
            List<User> allUsers = userMapper.selectList(null);

            // 这里可以根据 collectionName 过滤有权限的用户
            List<User> authorizedUsers = allUsers.stream()
                    .filter(user -> user.getStatus() != null && user.getStatus())
                    .collect(Collectors.toList());

            log.info("获取知识库 [{}] 授权用户成功，共 {} 人", collectionName, authorizedUsers.size());
            return Result.success(authorizedUsers);

        } catch (Exception e) {
            log.error("获取知识库用户失败", e);
            return Result.error(500, "获取知识库用户失败：" + e.getMessage());
        }
    }

    /**
     * 获取某个文件分片的使用用户
     */
    public Result<List<Map<String, Object>>> getFileUsers(String fileId, int chunkId) {
        try {
            List<Map<String, Object>> userList = new ArrayList<>();

            if (fileId == null || fileId.isBlank()) {
                return Result.error(400, "文件ID不能为空");
            }

            // 获取所有用户加载的知识库
            Set<String> userCollectionKeys = stringRedisTemplate.keys(RedisKeyConfig.userLoadCollectionsKey(0L).replace("0", "*"));

            if (userCollectionKeys != null) {
                for (String key : userCollectionKeys) {
                    // 从key中提取用户ID
                    String userIdStr = key.substring(key.lastIndexOf(":") + 1);
                    Long userId = Long.parseLong(userIdStr);

                    // 检查用户是否有该文件权限
                    String bitKey = RedisKeyConfig.userFileBitKey(userId, fileId);
                    Boolean hasPermission = stringRedisTemplate.opsForValue().getBit(bitKey, chunkId);

                    if (Boolean.TRUE.equals(hasPermission)) {
                        Map<String, Object> userInfo = new HashMap<>();
                        userInfo.put("userId", userId);
                        userInfo.put("fileId", fileId);
                        userInfo.put("chunkId", chunkId);

                        // 获取用户信息
                        User user = userMapper.selectById(userId);
                        if (user != null) {
                            userInfo.put("userName", user.getName());
                            userInfo.put("userRank", user.getUserRank());
                        }
                        userList.add(userInfo);
                    }
                }
            }

            log.info("获取文件 [{}] 分片 {} 的使用用户成功，共 {} 人", fileId, chunkId, userList.size());
            return Result.success(userList);

        } catch (Exception e) {
            log.error("获取文件用户失败", e);
            return Result.error(500, "获取文件用户失败：" + e.getMessage());
        }
    }

    /**
     * 后台处理文件：清理、重建、统计
     */
    public Result<String> processorFiles() {
        try {
            int cleanedCount = 0;

            // 获取所有文件哈希 Key
            Set<String> hashKeys = stringRedisTemplate.keys(RedisKeyConfig.fileHashKey("*"));
            if (hashKeys == null || hashKeys.isEmpty()) {
                return Result.success("没有需要处理的文件");
            }

            for (String hashKey : hashKeys) {
                String fileId = stringRedisTemplate.opsForValue().get(hashKey);
                if (fileId == null) continue;

                // 检查文件是否被任何知识库引用
                Set<String> collections = getCollectionsByFileId(fileId);
                if (collections.isEmpty()) {
                    // 没有被引用的文件，清理掉
                    try {
                        //milvusFileManager.deleteDocument(fileId);
                        stringRedisTemplate.delete(hashKey);
                        cleanedCount++;
                        log.info("清理无效文件: {}", fileId);
                    } catch (Exception e) {
                        log.warn("清理文件失败: {}", fileId, e);
                    }
                }
            }

            String message = String.format("文件处理完成，共清理无效文件：%d 个", cleanedCount);
            log.info(message);
            return Result.success(message);

        } catch (Exception e) {
            log.error("文件处理失败", e);
            return Result.error(500, "文件处理失败：" + e.getMessage());
        }
    }

    /**
     * 后台处理知识库：重建索引、清理空集合
     * @param collectionName 知识库名称，为空则处理所有
     */
    public Result<String> processorCollections(String collectionName) {
        try {
            if (collectionName == null || collectionName.isBlank()) {
                // 处理所有空集合
                Set<String> collections = getAllCollections().getData();
                if (collections != null) {
                    for (String coll : collections) {
                        processSingleCollection(coll);
                    }
                }
                return Result.success("所有知识库处理完成");
            } else {
                // 处理指定集合
                processSingleCollection(collectionName);
                return Result.success("知识库 [" + collectionName + "] 处理完成");
            }

        } catch (Exception e) {
            log.error("知识库处理失败: {}", collectionName, e);
            return Result.error(500, "知识库处理失败：" + e.getMessage());
        }
    }

    /**
     * 处理单个知识库
     */
    private void processSingleCollection(String collectionName) {
        try {
            // 检查集合是否为空
            String fileIdsKey = RedisKeyConfig.collectionFileIds(collectionName);
            Long fileCount = stringRedisTemplate.opsForSet().size(fileIdsKey);

            if (fileCount == null || fileCount == 0) {
                // 空集合，删除
                milvusCollectionService.drop(collectionName);
                stringRedisTemplate.delete(fileIdsKey);
                log.info("清理空集合: {}", collectionName);
            } else {
                log.info("集合 [{}] 包含 {} 个文件，跳过清理", collectionName, fileCount);
            }
        } catch (Exception e) {
            log.error("处理集合失败: {}", collectionName, e);
        }
    }
}