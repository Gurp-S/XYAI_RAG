package com.XYai.myai.rag.milvus;

import cn.hutool.core.util.StrUtil;
import com.XYai.myai.rag.milvus.POJO.FilePermission;
import com.XYai.myai.rag.milvus.POJO.MilvusCollections;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.POJO.User;
import com.alibaba.fastjson2.JSON;
import com.google.gson.JsonObject;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class MilvusAclManager {

    @Resource
    private final MilvusServiceClient milvusClient;
    @Resource
    private RedissonClient redissonClient;
    @Value("${spring.ai.vectorstore.milvus.databaseName:default}")
    private String databaseName;

    @Autowired
    public MilvusAclManager(MilvusServiceClient milvusClient) {
        this.milvusClient = milvusClient;
    }

    /**
     * 获取当前用户可见的集合列表（跨实例共享，Redis 优先）。
     */
    public List<String> getUserCollectionsAcl() {
        log.info("获取用户集合");
        Long userId = LoginUserInfoManager.get().getId();
        String redisKey = RedisKeyConfig.userCollectionsKey(userId);

        try {
            // 统一为字符串集合（不兼容历史混合类型数据）
            RSet<String> permissionSet = redissonClient.getSet(redisKey);

            // 直接读取集合内容
            if (!permissionSet.isEmpty()) {
                log.debug("Redis 缓存命中，userId: {}, count: {}", userId, permissionSet.size());
                log.info(String.valueOf(permissionSet));
                return new ArrayList<>(permissionSet);
            }

            log.debug("Redis 缓存为空，userId: {}", userId);

        } catch (Exception e) {
            log.warn("读取 Redis 权限缓存异常，降级处理。userId: {}, error: {}",
                    userId, e.getMessage(), e);
        }
        // 3. 缓存未命中或异常时的降级策略
        // TODO: 如果需要，这里可以添加 "查数据库 → 回写缓存" 的逻辑 (Cache-Aside)
        return List.of();
    }


    /**
     * 获取当前权限集合元数据(文档)
     * @param collectionName
     * @return
     */
    public metadataResult getCollectionMetadata(String collectionName) {
        User user = LoginUserInfoManager.get();
        Long userId = user.getId();
        String expr = String.format(
                "(metadata[\"ownerId\"] == %d) " +
                "OR (metadata[\"visibility\"] == \"public\") " +
                "OR (metadata[\"visibility\"] == \"groupPublic\" AND metadata[\"groupId\"] == \"%s\")",
                userId, user.getGroupId()
        );
        // 1. 构建查询参数
        QueryParam queryParam = QueryParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(collectionName)
                .withExpr(expr)
                .withOutFields(Arrays.asList("doc_id", "content", "metadata"))
                .build();

        // 2. 执行查询并检查响应状态
        try {
            R<QueryResults> response = milvusClient.query(queryParam);
            return new metadataResult(expr, response);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (msg.contains("collection not found") || msg.contains("collection not exist") || msg.contains("collection not exists")) {
                log.warn("查询 Milvus 集合元数据时集合不存在: collection={}, expr={}, reason={}", collectionName, expr, e.getMessage());
                // 返回空响应，调用方应处理 null 响应为 "no data / not exists"
                return new metadataResult(expr, null);
            }
            // 未知错误继续向上抛出，保持原有行为
            throw new IllegalStateException("查询集合元数据失败: " + collectionName, e);
        }
    }
    
    /**
     * 保存当前用户可见集合列表（合并写入 Redis）。
     */
    public void saveUserCollectionsAcl(List<String> visibleCollectionNames) {
        try {
            Long userId = LoginUserInfoManager.get().getId();
            String redisKey = RedisKeyConfig.userCollectionsKey(userId);
            RSet<String> set = redissonClient.getSet(redisKey);

            // 原子清空 + 批量添加
            set.clear();
            if (visibleCollectionNames != null && !visibleCollectionNames.isEmpty()) {
                set.addAll(visibleCollectionNames);
            }

        } catch (Exception e) {
            log.warn("写入用户集合权限缓存失败, userId: {}, error: {}",
                    LoginUserInfoManager.get().getId(), e.getMessage(), e);
        }
    }

    /**
     * 增量写入用户可见集合（只添加，不覆盖），用于 exists() 等在线发现场景。
     */
    public void addUserCollectionsAcl(Collection<String> visibleCollectionNames) {
        if (visibleCollectionNames == null || visibleCollectionNames.isEmpty()) return;
        try {
            Long userId = LoginUserInfoManager.get().getId();
            String redisKey = RedisKeyConfig.userCollectionsKey(userId);
            RSet<String> set = redissonClient.getSet(redisKey);
            set.addAll(visibleCollectionNames);
        } catch (Exception e) {
            log.warn("增量写入用户集合权限缓存失败, userId: {}, error: {}",
                    LoginUserInfoManager.get().getId(), e.getMessage(), e);
        }
    }

    /**
     * 获取集合的权限
     * @param collectionName 集合名
     * @return EXISTS_HAVE_ACL = 有权限 | EXISTS_NO_ACL = 无权限
     */
    public String getCollectionAcl(String collectionName) {
        // 获取当前用户可见的集合列表
        List<String> permissionSet = getUserCollectionsAcl();

        // 判空
        if (permissionSet == null) {
            return MilvusCollections.UNKNOWN_EXISTS;
        }

        // 判断是否包含
        boolean hasPermission = permissionSet.stream()
                .anyMatch(name -> StrUtil.equals(name, collectionName));

        // 返回权限结果权限有->存在有权限,缓存权限没有->不知道是不存在还是无权限
        return hasPermission ? MilvusCollections.EXISTS_HAVE_ACL : MilvusCollections.UNKNOWN_EXISTS;
    }

    /**
     * 删除文档权限（删除 Redis 里的文件权限缓存）
     */
    public Long deleteDocumentAcl(String fileId) {
        User user = LoginUserInfoManager.get();
        Long userId = user.getId();
        try {
            // 删除文件权限
            String redisKey = RedisKeyConfig.fileHashKey(fileId);
            RBucket<String> fileRedis = redissonClient.getBucket(redisKey);
            String json = fileRedis.get();
            FilePermission filePermission = StrUtil.isBlank(json) ? null : JSON.parseObject(json, FilePermission.class);
            if (filePermission != null) {
                List<Long> userIds = ensureMutableUserIds(filePermission.getUserIds());
                userIds.remove(userId);

                if (!userIds.isEmpty()) {
                    filePermission.setUserIds(userIds);
                    fileRedis.set(JSON.toJSONString(filePermission));
                } else {
                    fileRedis.delete();
                }
                return 1L;
            }
            log.info("删除文档权限失败: userId={}, fileId={}", userId, fileId);
            return 0L;
        } catch (Exception e) {
            log.error("删除文档权限失败: {}", e.getMessage(), e);
            return 0L;
        }
    }

    /**
     * 复制文件权限
     * 无论原集合权限如何，直接给新文件设置当前用户权限
     */
    public List<InsertParam.Field> copyFilePermission(String collectionName, List<InsertParam.Field> originalFields) {
        User user = LoginUserInfoManager.get();
        Long userId = user.getId();
        String groupId = user.getGroupId();
        log.debug("复制文件权限并重写 metadata, collectionName={}", collectionName);

        // 构建权限元数据
        JsonObject permissionMeta = new JsonObject();
        permissionMeta.addProperty("ownerId", userId);
        permissionMeta.addProperty("visibility", "private");
        permissionMeta.addProperty("groupId", groupId);

        // 复制原有字段，避免修改入参
        List<InsertParam.Field> newFields = new ArrayList<>(originalFields);

        // 移除旧的 metadata（如果存在）
        newFields.removeIf(field -> "metadata".equals(field.getName()));

        // 添加新权限 metadata
        InsertParam.Field metaField = InsertParam.Field.builder()
                .name("metadata")
                .values(Collections.singletonList(permissionMeta))
                .build();

        newFields.add(metaField);
        return newFields;
    }

    public void addFileUserACl(String fileId, String collectionName){
        Document document = new Document("");
        document.getMetadata().put("fileId",fileId);
        addFileUserACl(Collections.singletonList(document), collectionName);
    }

    public void addFileUserACl(List<Document> documents, String collectionName) {
        User user = LoginUserInfoManager.get();
        Long userId = user.getId();
        for (Document document : documents) {
            Object fileIdValue = document.getMetadata().get("fileId");
            if (fileIdValue == null) {
                log.warn("文档缺少 fileId 元数据，跳过权限写入");
                continue;
            }

            String fileId = String.valueOf(fileIdValue);
            String redisKey = RedisKeyConfig.fileHashKey(fileId);
            RBucket<String> fileRedis = redissonClient.getBucket(redisKey);
            String json = fileRedis.get();
            FilePermission filePermission = StrUtil.isBlank(json) ? null : JSON.parseObject(json, FilePermission.class);
            if (filePermission != null) {
                List<Long> userIds = ensureMutableUserIds(filePermission.getUserIds());
                if (!userIds.contains(userId)) {
                    userIds.add(userId);
                }
                filePermission.setUserIds(userIds);
            } else {//构建文件权限对象
                filePermission = FilePermission.builder()
                        .collectionName(collectionName)
                        .fileId(fileId)
                        .userIds(new ArrayList<>(Collections.singletonList(userId)))
                        .visibility("private")
                        .build();
            }
            fileRedis.set(JSON.toJSONString(filePermission));
        }
    }

    private List<Long> ensureMutableUserIds(List<Long> userIds) {
        if (userIds == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(userIds);
    }

    public void dropCollectionAcl(String collectionName) {
        //TODO删除文件下的所有该用户的权限
        Long userId = LoginUserInfoManager.get().getId();
        String redisKey = RedisKeyConfig.userCollectionsKey(userId);
    }

    public void userCollectionLoadAcl() {
        //
    }


    public record metadataResult(String expr, R<QueryResults> response) {
    }
}
