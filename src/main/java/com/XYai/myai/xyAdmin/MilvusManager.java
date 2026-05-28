package com.XYai.myai.xyAdmin;

import com.XYai.myai.commonUtils.redis.RedisBitSetUtils;
import com.XYai.myai.commonUtils.redis.RedisKeyConfig;
import com.XYai.myai.config.Result;
import com.XYai.myai.mapper.FileRecordMapper;
import com.XYai.myai.mapper.UserMapper;
import com.XYai.myai.rag.milvus.MilvusAclManager;
import com.XYai.myai.rag.milvus.MilvusCollectionService;
import com.XYai.myai.user.pojo.User;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.GetCollectionStatisticsResponse;
import io.milvus.grpc.KeyValuePair;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.collection.GetCollectionStatisticsParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Milvus 向量库管理类
 * 提供后台管理：文件/集合/用户权限/数据处理
 * 支持分页、搜索、排序、缓存刷新与数据清理
 */
@Slf4j
@RestController
@RequestMapping("/xyAdmin/milvus")
public class MilvusManager {

    private static final long CACHE_TTL_MS = 60000;
    @Resource
    private UserMapper userMapper;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private FileRecordMapper fileRecordMapper;
    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String defaultCollectionName;
    @Value("${spring.ai.vectorstore.milvus.databaseName:my_xy}")
    private String databaseName;
    @Resource
    private MilvusServiceClient milvusClient;
    @Resource
    private MilvusCollectionService milvusCollectionService;
    // 缓存文件列表
    private List<Map<String, Object>> cachedFileList = null;
    private long cacheTimestamp = 0;

    /**
     * 分页获取文件信息（支持搜索、排序）
     */
    @GetMapping("/files")
    public Result<Map<String, Object>> getFilesPaged(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String fileId,
            @RequestParam(defaultValue = "useCount") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {
        try {
            List<Map<String, Object>> allFiles = getOrRefreshFileCache();

            // 文件ID模糊搜索
            List<Map<String, Object>> filtered = new ArrayList<>();
            for (Map<String, Object> file : allFiles) {
                String fid = (String) file.get("fileId");
                if (fileId != null && !fileId.isBlank() && (fid == null || !fid.contains(fileId))) {
                    continue;
                }
                filtered.add(file);
            }

            // 排序
            Comparator<Map<String, Object>> comparator;
            if ("useCount".equalsIgnoreCase(sortBy)) {
                comparator = Comparator.comparingInt(f -> ((Number) f.getOrDefault("useCount", 0)).intValue());
            } else {
                comparator = Comparator.comparing(f -> (String) f.getOrDefault("fileId", ""));
            }
            if ("desc".equalsIgnoreCase(sortOrder)) {
                comparator = comparator.reversed();
            }
            filtered.sort(comparator);

            // 分页
            int total = filtered.size();
            int fromIndex = (page - 1) * size;
            int toIndex = Math.min(fromIndex + size, total);
            List<Map<String, Object>> pageData = fromIndex < total ? filtered.subList(fromIndex, toIndex)
                    : new ArrayList<>();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", total);
            result.put("page", page);
            result.put("size", size);
            result.put("records", pageData);
            return Result.success(result);
        } catch (Exception e) {
            log.error("getFilesPaged 失败", e);
            return Result.error(500, "获取文件列表失败：" + e.getMessage());
        }
    }

    /**
     * 获取或刷新文件缓存（从 Redis 和 Milvus 收集所有已知文件）
     */
    private List<Map<String, Object>> getOrRefreshFileCache() {
        long now = System.currentTimeMillis();
        if (cachedFileList != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedFileList;
        }

        Set<String> allFileIds = new LinkedHashSet<>();
        try {
            R<QueryResults> resp = milvusClient.query(QueryParam.newBuilder()
                    .withDatabaseName(databaseName)
                    .withCollectionName(defaultCollectionName)
                    .withExpr("doc_id != \"\"")
                    .withOutFields(List.of("doc_id"))
                    .withLimit(10000L)
                    .build());
            if (resp.getStatus() == R.Status.Success.getCode() && resp.getData() != null) {
                QueryResultsWrapper wrapper = new QueryResultsWrapper(resp.getData());
                for (QueryResultsWrapper.RowRecord record : wrapper.getRowRecords()) {
                    Object docId = record.get("doc_id");
                    if (docId instanceof String s && !s.isBlank()) {
                        int idx = s.lastIndexOf(':');
                        if (idx > 0)
                            allFileIds.add(s.substring(0, idx));
                        else
                            allFileIds.add(s);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("从 Milvus 获取 fileId 失败: {}", e.getMessage());
        }

        List<Map<String, Object>> resultList = new ArrayList<>();
        for (String fid : allFileIds) {
            Map<String, Object> fileInfo = new LinkedHashMap<>();
            fileInfo.put("fileId", fid);
            fileInfo.put("collections", getCollectionsByFileId(fid));
            fileInfo.put("useCount", countFileUsers(fid));
            resultList.add(fileInfo);
        }

        cachedFileList = resultList;
        cacheTimestamp = now;
        log.info("刷新文件缓存: {} 个文件", resultList.size());
        return resultList;
    }

    /**
     * 统计文件使用用户数
     */
    private long countFileUsers(String fileId) {
        long count = 0;
        try {
            Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(
                    RedisKeyConfig.userFileBitKeyPatternByFileId(fileId));
            for (String key : keys) {
                byte[] raw = stringRedisTemplate.execute((RedisCallback<byte[]>) conn -> conn.stringCommands()
                        .get(key.getBytes(StandardCharsets.UTF_8)));
                if (raw != null && RedisBitSetUtils.bitSetFromRedisBytes(raw).cardinality() > 0)
                    count++;
            }
        } catch (Exception e) {
            log.warn("统计文件用户失败: {}", e.getMessage());
        }
        return count;
    }

    /**
     * 根据文件ID获取关联的知识库集合
     */
    private Set<String> getCollectionsByFileId(String fileId) {
        Set<String> result = new HashSet<>();
        try {
            // 匹配所有 collection:files:* 的 key
            Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(
                    RedisKeyConfig.PREFIX + "collection:files:*");

            for (String key : keys) {
                // 提取集合名称：key 格式为 xyai:collection:files:{collectionName}
                String collectionName = extractCollectionNameFromKey(key);
                if (collectionName == null) {
                    continue;
                }

                // 检查该集合的 SET 中是否包含该 fileId 的分块
                Set<String> set = stringRedisTemplate.opsForSet()
                        .members(RedisKeyConfig.collectionFileIds(collectionName));
                for (String entry : set) {
                    if (entry != null && entry.startsWith(fileId + ":")) {
                        result.add(collectionName);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("获取文件关联知识库失败: {}", e.getMessage());
        }
        return result;
    }

    /**
     * 从 Redis Key 中提取知识库名称
     * 支持格式：xyai:collection:files:{collectionName}
     */
    private String extractCollectionNameFromKey(String key) {
        if (key == null || !key.startsWith(RedisKeyConfig.PREFIX)) {
            return null;
        }
        String withoutPrefix = key.substring(RedisKeyConfig.PREFIX.length());
        String[] parts = withoutPrefix.split(":");
        if (parts.length >= 3 && "collection".equals(parts[0]) && "files".equals(parts[1])) {
            return withoutPrefix.substring("collection:files:".length());
        }
        return null;
    }

    // --------------------------- 知识库相关 ---------------------------

    @GetMapping("/collections")
    public Result<Map<String, Object>> getCollectionsPaged(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String name,
            @RequestParam(defaultValue = "name") String sortBy,
            @RequestParam(defaultValue = "asc") String sortOrder) {
        try {
            Set<String> allNames = new LinkedHashSet<>();
            Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(
                    RedisKeyConfig.PREFIX + "collection:files:*");
            for (String key : keys) {
                String collectionName = extractCollectionNameFromKey(key);
                if (collectionName == null || collectionName.isBlank()) {
                    continue;
                }
                // 可选的过滤条件
                if (name != null && !name.isBlank() && !collectionName.contains(name)) {
                    continue;
                }
                allNames.add(collectionName);
            }

            List<Map<String, Object>> list = new ArrayList<>();
            for (String collName : allNames) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("name", collName);
                info.put("fileCount", countCollectionFiles(collName));
                info.put("userCount", countCollectionUsers(collName));
                list.add(info);
            }

            // 排序
            Comparator<Map<String, Object>> comparator;
            if ("fileCount".equalsIgnoreCase(sortBy)) {
                comparator = Comparator.comparingInt(c -> (Integer) c.getOrDefault("fileCount", 0));
            } else {
                comparator = Comparator.comparing(c -> (String) c.getOrDefault("name", ""));
            }
            if ("desc".equalsIgnoreCase(sortOrder)) {
                comparator = comparator.reversed();
            }
            list.sort(comparator);

            int total = list.size();
            int from = (page - 1) * size;
            int to = Math.min(from + size, total);
            List<Map<String, Object>> pageData = from < total ? list.subList(from, to) : new ArrayList<>();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", total);
            result.put("page", page);
            result.put("size", size);
            result.put("records", pageData);
            return Result.success(result);
        } catch (Exception e) {
            log.error("获取知识库列表失败", e);
            return Result.error(500, "获取知识库列表失败：" + e.getMessage());
        }
    }

    private Integer countCollectionFiles(String collectionName) {
        try {
            Long size = stringRedisTemplate.opsForSet().size(RedisKeyConfig.collectionFileIds(collectionName));
            return size != null ? size.intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private Integer countCollectionUsers(String collectionName) {
        int count = 0;
        try {
            Iterable<String> userKeys = redissonClient.getKeys().getKeysByPattern(
                    RedisKeyConfig.PREFIX + "user:collections:*");
            for (String key : userKeys) {
                if (key.contains(":unloaded:"))
                    continue;
                if (Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(key, collectionName)))
                    count++;
            }
        } catch (Exception e) {
            log.warn("统计知识库用户失败", e);
        }
        return count;
    }

    @GetMapping("/collection/users")
    public Result<Map<String, Object>> getCollectionUsersPaged(
            @RequestParam String collectionName,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            if (collectionName == null || collectionName.isBlank()) {
                return Result.error(400, "知识库名称不能为空");
            }
            List<User> authorizedUsers = new ArrayList<>();
            List<User> allUsers = userMapper.selectList(null);
            for (User user : allUsers) {
                if (user.getId() != null) {
                    if (Boolean.TRUE.equals(stringRedisTemplate.opsForSet()
                            .isMember(RedisKeyConfig.userLoadCollectionsKey(user.getId()), collectionName))) {
                        user.setPassword(null);
                        authorizedUsers.add(user);
                    }
                }
            }

            int total = authorizedUsers.size();
            int from = (page - 1) * size, to = Math.min(from + size, total);
            List<User> pageData = from < total ? authorizedUsers.subList(from, to) : new ArrayList<>();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", total);
            result.put("page", page);
            result.put("size", size);
            result.put("records", pageData);
            return Result.success(result);
        } catch (Exception e) {
            log.error("获取知识库用户失败", e);
            return Result.error(500, "获取知识库用户失败：" + e.getMessage());
        }
    }

    @PostMapping("/cache/refresh")
    public Result<String> refreshCache() {
        cachedFileList = null;
        cacheTimestamp = 0;
        getOrRefreshFileCache();
        return Result.success("缓存已刷新");
    }

    @GetMapping("/file/users")
    public Result<Map<String, Object>> getFileUsersPaged(
            @RequestParam String fileId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            // 查找所有用户的 user:filebits:userId:fileId 键，收集 userId
            List<Long> userIds = new ArrayList<>();
            Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(
                    RedisKeyConfig.userFileBitKeyPatternByFileId(fileId));
            for (String key : keys) {
                String[] parts = key.split(":");
                if (parts.length >= 5) {
                    userIds.add(Long.parseLong(parts[4]));
                }
            }

            // 批量查询用户
            List<User> allUsers = userIds.isEmpty() ? List.of() : userMapper.selectBatchIds(userIds);
            allUsers.forEach(u -> u.setPassword(null));

            int total = allUsers.size();
            int from = (page - 1) * size, to = Math.min(from + size, total);
            List<User> pageData = from < total ? allUsers.subList(from, to) : new ArrayList<>();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("total", total);
            result.put("page", page);
            result.put("size", size);
            result.put("records", pageData);
            return Result.success(result);
        } catch (Exception e) {
            log.error("获取文件用户失败", e);
            return Result.error(500, "获取文件用户失败：" + e.getMessage());
        }
    }

    // --------------------------- 数据处理 ---------------------------

    /**
     * 后台处理文件：清理无效文件（无关联知识库的文件）
     * 同时删除 Redis 中相关键和 xy_file_record 表中的记录
     */
    @PostMapping("/process/files")
    public Result<String> processorFiles() {
        try {
            int cleanedRedis = 0;
            int cleanedDb = 0;
            // 获取所有 file:hash:* 键
            Iterable<String> hashKeys = redissonClient.getKeys().getKeysByPattern(
                    RedisKeyConfig.PREFIX + "file:hash:*");
            for (String hashKey : hashKeys) {
                // fileHashKey 存储的是 Set，包含关联的 collectionName，所以需要遍历成员
                Set<String> hashSet = stringRedisTemplate.opsForSet().members(hashKey);
                boolean hasCollections = false;
                for (String member : hashSet) {
                    // 成员就是 collectionName
                    if (member != null && !member.isBlank()) {
                        hasCollections = true;
                        break;
                    }
                }
                if (!hasCollections) {
                    // 删除该哈希键
                    stringRedisTemplate.delete(hashKey);
                    cleanedRedis++;
                    // 可选：从 key 中提取 fileId 并删除 xy_file_record 记录
                    String tagged = hashKey.substring(hashKey.lastIndexOf(":") + 1);
                    String fileId = RedisKeyConfig.stripHashTag(tagged);
                    try {
                        fileRecordMapper.deleteById(fileId);
                        cleanedDb++;
                    } catch (Exception ex) {
                        log.warn("删除 xy_file_record 记录失败: {}", fileId, ex);
                    }
                }
            }
            // 清除缓存
            cachedFileList = null;
            return Result.success(String.format(
                    "文件处理完成，清理 Redis 键: %d 个，数据库记录: %d 条", cleanedRedis, cleanedDb));
        } catch (Exception e) {
            log.error("文件处理失败", e);
            return Result.error(500, "文件处理失败：" + e.getMessage());
        }
    }

    /**
     * 后台处理知识库：删除空集合（没有文件的集合）
     */
    @PostMapping("/process/collections")
    public Result<String> processorCollections(@RequestParam(required = false) String collectionName) {
        try {
            if (collectionName != null && !collectionName.isBlank()) {
                processSingleCollection(collectionName);
                return Result.success("知识库 [" + collectionName + "] 处理完成");
            } else {
                // 处理所有知识库
                Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(
                        RedisKeyConfig.PREFIX + "collection:files:*");
                int count = 0;
                for (String key : keys) {
                    String suffix = key.substring(RedisKeyConfig.PREFIX.length());
                    String[] parts = suffix.split(":");
                    String candidate = key.substring(key.lastIndexOf(":") + 1);
                    if (!candidate.matches("^[a-zA-Z0-9_]{1,64}$"))
                        continue;
                    String coll = parts[2];
                    processSingleCollection(coll);
                    count++;
                }
                return Result.success("所有知识库处理完成，共处理 " + count + " 个");
            }
        } catch (Exception e) {
            log.error("知识库处理失败", e);
            return Result.error(500, "知识库处理失败：" + e.getMessage());
        }
    }

    private void processSingleCollection(String collectionName) {
        try {
            Long fileCount = stringRedisTemplate.opsForSet().size(RedisKeyConfig.collectionFileIds(collectionName));
            if (fileCount == null || fileCount == 0) {
                // 删除 Milvus 物理集合（如果存在）
                milvusCollectionService.drop(collectionName);
                // 删除 Redis 中的集合文件键
                stringRedisTemplate.delete(RedisKeyConfig.collectionFileIds(collectionName));
                log.info("清理空集合: {}", collectionName);
            }
        } catch (Exception e) {
            log.error("处理集合失败: {}", collectionName, e);
        }
    }

    // --------------------------- 物理集合统计与查询 ---------------------------

    @GetMapping("/stats/physical")
    public Result<Map<String, Object>> getPhysicalCollectionStats() {
        try {
            R<GetCollectionStatisticsResponse> resp = milvusClient.getCollectionStatistics(
                    GetCollectionStatisticsParam.newBuilder()
                            .withDatabaseName(databaseName)
                            .withCollectionName(defaultCollectionName)
                            .build());
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("collectionName", defaultCollectionName);
            stats.put("databaseName", databaseName);
            long rowCount = 0;
            if (resp != null && resp.getStatus() == R.Status.Success.getCode() && resp.getData() != null) {
                for (KeyValuePair kv : resp.getData().getStatsList()) {
                    if ("row_count".equals(kv.getKey())) {
                        rowCount = Long.parseLong(kv.getValue());
                        break;
                    }
                }
            }
            stats.put("rowCount", rowCount);
            return Result.success(stats);
        } catch (Exception e) {
            log.error("获取统计失败", e);
            return Result.error(500, "查询失败");
        }
    }

    @GetMapping("/query")
    public Result<List<Map<String, Object>>> queryMilvus(
            @RequestParam(defaultValue = "doc_id != \"\"") String expr,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            QueryParam param = QueryParam.newBuilder()
                    .withDatabaseName(databaseName)
                    .withCollectionName(defaultCollectionName)
                    .withExpr(expr)
                    .withOutFields(List.of("doc_id", "metadata"))
                    .withLimit((long) limit)
                    .build();
            R<QueryResults> response = milvusClient.query(param);
            if (response.getStatus() != R.Status.Success.getCode()) {
                return Result.error(500, response.getMessage());
            }
            List<Map<String, Object>> result = new ArrayList<>();
            QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
            for (QueryResultsWrapper.RowRecord record : wrapper.getRowRecords()) {
                result.add(new LinkedHashMap<>(record.getFieldValues()));
            }
            return Result.success(result);
        } catch (Exception e) {
            log.error("Milvus 查询异常", e);
            return Result.error(500, "查询异常: " + e.getMessage());
        }
    }

    @PostMapping("/process/files/unused")
    public Result<String> cleanUnusedFiles() {
        int removed = 0;
        try {
            List<Map<String, Object>> allFiles = getOrRefreshFileCache();
            for (Map<String, Object> file : allFiles) {
                String fid = (String) file.get("fileId");
                if (countFileUsers(fid) == 0) {
                    // 删除 Redis 中与该文件相关的键（如 file:hash:* 和集合中的条目）
                    // 先清理 file:hash: 键
                    Iterable<String> hashKeys = redissonClient.getKeys().getKeysByPattern(
                            RedisKeyConfig.fileHashKey(fid));
                    for (String hk : hashKeys) {
                        stringRedisTemplate.delete(hk);
                    }
                    // 清理 collection:files:* 中涉及该文件的条目
                    Iterable<String> collKeys = redissonClient.getKeys().getKeysByPattern(
                            RedisKeyConfig.PREFIX + "collection:files:*");
                    for (String ck : collKeys) {
                        Set<String> set = stringRedisTemplate.opsForSet().members(ck);
                        for (String entry : set) {
                            if (entry != null && entry.startsWith(fid + ":")) {
                                stringRedisTemplate.opsForSet().remove(ck, entry);
                            }
                        }
                    }
                    removed++;
                    log.info("清理无用户文件: {}", fid);
                }
            }
            // 刷新缓存
            cachedFileList = null;
            return Result.success("已清理 " + removed + " 个无用户文件");
        } catch (Exception e) {
            return Result.error(500, "清理失败: " + e.getMessage());
        }
    }

    @PostMapping("/process/collections/unused")
    public Result<String> cleanUnusedCollections() {
        int removed = 0;
        try {
            Set<String> allNames = new LinkedHashSet<>();
            Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(
                    RedisKeyConfig.PREFIX + "collection:files:*");
            for (String key : keys) {
                String candidate = key.substring(key.lastIndexOf(":") + 1);
                if (!candidate.matches("^[a-zA-Z0-9_]{1,64}$"))
                    continue;
            }
            for (String coll : allNames) {
                if (countCollectionUsers(coll) == 0) {
                    // 删除 Redis 中的集合信息，并尝试删除 Milvus 物理集合
                    stringRedisTemplate.delete(RedisKeyConfig.collectionFileIds(coll));
                    milvusCollectionService.drop(coll);
                    removed++;
                    log.info("清理无用户集合: {}", coll);
                }
            }
            return Result.success("已清理 " + removed + " 个无用户集合");
        } catch (Exception e) {
            return Result.error(500, "清理失败: " + e.getMessage());
        }
    }
}