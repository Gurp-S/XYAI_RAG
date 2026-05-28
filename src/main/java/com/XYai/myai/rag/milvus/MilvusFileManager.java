package com.XYai.myai.rag.milvus;

import com.XYai.myai.commonUtils.redis.RedisBitSetUtils;
import com.XYai.myai.commonUtils.redis.RedisKeyConfig;
import com.XYai.myai.config.Result;
import com.XYai.myai.rag.etlpipeline.UploadTaskStore;
import com.XYai.myai.rag.etlpipeline.pojo.SkipFileInfo;
import com.XYai.myai.user.LoginUserInfoManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MilvusFileManager {

    private static final int MAX_METADATA_QUERY_LIMIT = 10000;
    private static final int MAX_OR_CLAUSES = 5000;
    private static final int MAX_CONCURRENT_COLLECTION_QUERIES = 3;
    @Resource
    private MilvusAclManager milvusAclManager;
    @Resource
    private UploadTaskStore uploadTaskStore;
    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String defaultCollectionName;
    @Value("${spring.ai.vectorstore.milvus.databaseName:my_xy}")
    private String databaseName;
    @Resource
    private MilvusServiceClient milvusClient;
    @Resource
    private MilvusCollectionService milvusCollectionService;
    @Resource
    private MilvusMetadataFilter milvusMetadataFilter;
    @Resource(name = "milvusExecutor")
    private TaskExecutor milvusExecutor;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 解析 Milvus 查询结果为 Map 列表，过滤掉 Google Gson 对象。
     */
    @NotNull
    public List<Map<String, Object>> getMetadataResultByMilvusClient(R<QueryResults> response) {
        if (response == null || response.getData() == null)
            return List.of();

        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        List<QueryResultsWrapper.RowRecord> rowRecords = wrapper.getRowRecords();
        List<Map<String, Object>> result = new ArrayList<>(rowRecords.size());

        for (QueryResultsWrapper.RowRecord rowRecord : rowRecords) {
            Map<String, Object> original = rowRecord.getFieldValues();
            Map<String, Object> clean = new HashMap<>(original.size());
            original.forEach((key, value) -> {
                // 将 Google Gson 复杂对象转为字符串，其他类型直接保存
                if (value instanceof com.google.gson.JsonElement) {
                    clean.put(key, value.toString());
                } else {
                    clean.put(key, value);
                }
            });
            result.add(clean);
        }
        return result;
    }

    /**
     * 文件去重判断入口（使用 Redis 原子操作避免并发问题）。
     * 无文件:
     * fileHash -> UP_FILE
     * 有文件:
     * 用户有此文件权限:
     * 集合有此文件权限:
     * 有全部分块():
     * 记录 WARN，SKIP_FILE
     * 无部分分块():
     * 检查缺失 chunk 在库中是否存在
     * 存在的直接补用户权限，不存在的加入 upChunks
     * 最终：
     * 若 upChunks 为空 -> SKIP_FILE (全部补齐)
     * 否则 -> UP_CHUNK
     * 集合无文件权限():
     * 复制整个文件权限 (COPY_FILE)
     * 添加集合到 fileHashKey
     * 用户无此文件权限:
     * 有其他用户拥有此文件权限():
     * 复制用户和集合分块权限 (COPY_FILE)
     * 无其他用户拥有此文件权限(fileHash 孤儿体):
     * 记录 WARN，然后 UP_FILE
     */
    public SkipFileInfo generateFile(String fileHash, String collectionName, String taskId) {
        try {
            SkipFileInfo result = new SkipFileInfo();
            reportFetchTask(taskId);

            long userId = LoginUserInfoManager.getUserId();
            String redisKey = RedisKeyConfig.fileHashKey(fileHash);

            // 1. 检查文件哈希是否已存在
            boolean exists = stringRedisTemplate.hasKey(redisKey);
            if (!exists) {
                result.setSkipStatus(SkipFileInfo.UP_FILE);
                return result;
            }
            // 2. 获取文件块大小（从 Redis Set 中直接读取）
            int chunkSize = getFileChunkSize(fileHash, stringRedisTemplate.opsForSet().randomMember(redisKey));
            log.info("chunkSize:{}", chunkSize);
            // 3. 同一用户、同一集合、同一文件的情况
            if (Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(redisKey, collectionName)) &&
                    milvusAclManager.getCollectionAcl(collectionName) &&
                    milvusAclManager.getFileAcl(fileHash)) {
                String userBitKey = RedisKeyConfig.userFileBitKey(userId, fileHash);
                String collectionBitKey = RedisKeyConfig.collectionFileChunkBitKey(collectionName, fileHash);
                byte[] userRaw = stringRedisTemplate.execute((RedisCallback<byte[]>) conn -> conn.stringCommands()
                        .get(userBitKey.getBytes(StandardCharsets.UTF_8)));
                byte[] collectionRaw = stringRedisTemplate.execute((RedisCallback<byte[]>) conn -> conn.stringCommands()
                        .get(collectionBitKey.getBytes(StandardCharsets.UTF_8)));
                BitSet localBits = (userRaw != null) ? RedisBitSetUtils.bitSetFromRedisBytes(userRaw) : new BitSet();
                BitSet collectionLocalBits = (collectionRaw != null) ? RedisBitSetUtils.bitSetFromRedisBytes(collectionRaw) : new BitSet();
                int existingCount = localBits.cardinality();
                int collectionExistingCount = collectionLocalBits.cardinality();
                log.info("existingCount:{},collectionExistingCount:{}",existingCount,collectionExistingCount);
                if (existingCount == chunkSize && collectionExistingCount == chunkSize) {
                    result.setSkipStatus(SkipFileInfo.SKIP_FILE);
                    return result;
                }
                // 扫描缺失 chunk
                List<Integer> missing = new ArrayList<>();
                for (int i = 1; i <= chunkSize; i++) {
                    if (!localBits.get(i) || !collectionLocalBits.get(i)) {
                        missing.add(i);
                    }
                }
                // 批量检查缺失 chunk 在库中是否存在
                List<Object> existsResults = stringRedisTemplate.executePipelined((RedisCallback<Object>) conn -> {
                    for (Integer id : missing) {
                        byte[] ck = RedisKeyConfig.fileChunkUserCountKey(fileHash, id).getBytes(StandardCharsets.UTF_8);
                        conn.keyCommands().exists(ck);
                    }
                    return null;
                });

                // 处理结果：库中存在的直接赋权，不存在的需上传
                boolean changed = false;
                for (int j = 0; j < missing.size(); j++) {
                    Integer id = missing.get(j);
                    boolean chunkInDb = (Boolean) existsResults.get(j);
                    if (chunkInDb) {
                        collectionLocalBits.set(id);
                        localBits.set(id);
                        changed = true;
                    } else {
                        result.getUpChunks().add(id);
                    }
                }
                // 有新增授权时写回 bitmap
                if (changed) {
                    final byte[] finalBytes = RedisBitSetUtils.bitSetToRedisBytes(localBits);
                    stringRedisTemplate.execute((RedisCallback<Object>) conn -> {
                        conn.stringCommands().set(userBitKey.getBytes(StandardCharsets.UTF_8), finalBytes);
                        return null;
                    });
                    final byte[] collectionFinalBytes = RedisBitSetUtils.bitSetToRedisBytes(collectionLocalBits);
                    stringRedisTemplate.execute((RedisCallback<Object>) conn -> {
                        conn.stringCommands().set(collectionBitKey.getBytes(StandardCharsets.UTF_8), collectionFinalBytes);
                        return null;
                    });
                }
                log.info("跳过文件:{}", result.getUpChunks());
                result.setSkipStatus(result.getUpChunks().isEmpty() ? SkipFileInfo.SKIP_FILE : SkipFileInfo.UP_CHUNK);
                return result;
            }

            // 4. 不同集合或新用户：复制整个文件
            log.info("复制文件权限,文件分块大小:{}", chunkSize);
            milvusAclManager.addFileUserACl(fileHash, collectionName, chunkSize);
            stringRedisTemplate.opsForSet().add(redisKey, collectionName);
            result.setSkipStatus(SkipFileInfo.COPY_FILE);
            return result;

        } catch (Exception e) {
            throw new RuntimeException("检查文件状态失败", e);
        }
    }

    /**
     * 获取文件的总分块数，从 Redis Hash 中读取 fileId:chunkSize。
     * 期望 Redis 中存储结构：fileHash -> field "chunkSize" -> value
     */
    private int getFileChunkSize(String fileHash, String collectionName) {
        Set<String> fileIds = stringRedisTemplate.opsForSet().members(RedisKeyConfig.collectionFileIds(collectionName));
        if (fileIds == null || fileIds.isEmpty()) {
            return 0;
        }
        // 遍历 Set，寻找 "fileHash:chunkSize" 格式的数据
        for (String entry : fileIds) {
            if (entry.startsWith(fileHash + ":")) {
                try {
                    return Integer.parseInt(entry.substring(entry.indexOf(":") + 1));
                } catch (NumberFormatException e) {
                    log.warn("chunkSize 解析失败: {}", entry);
                    return 0;
                }
            }
        }
        return 0;
    }
    //
    // /**
    // * 向 Milvus 集合添加文档（自动向量化），保证幂等性。
    // */
    // public Result<String> add(String collectionName, List<Document> documents) {
    // if (documents == null || documents.isEmpty()) {
    // return Result.error(400, "文档为空");
    // }
    //
    // if (!milvusCollectionService.exists(collectionName)) {
    // return Result.error(404, "集合不存在或无权限: " + collectionName);
    // }
    //
    // // 记录 ACL 权限
    // milvusAclManager.addFileUserACl(documents, collectionName);
    //
    // log.info("成功添加 {} 个文档到集合 {} ", documents.size(), collectionName);
    // return Result.success("添加成功");
    // }

    /**
     * 删除文件分块权限（实际向量数据保留，由 Milvus 生命周期管理）。
     */
    public void deleteDocument(String collectionName, int chunkId, String fileId) {
        milvusAclManager.deleteDocumentAcl(collectionName, chunkId, fileId);
    }

    /**
     * 流式计算文件 SHA-256 哈希。
     */
    public String calculateFileHash(MultipartFile file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = file.getInputStream()) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                digest.update(buffer, 0, len);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 获取集合内用户可见的文件元数据（全量），带权限过滤与 Milvus 查询。
     *
     * @deprecated 请使用 {@link #getUserCollectionFiles(String, String, int)} 进行分页查询
     */
    @Deprecated
    public List<Map<String, Object>> getUserCollectionFiles(String collectionName) {
        CursorPage<Map<String, Object>> page = getUserCollectionFiles(collectionName, null, Integer.MAX_VALUE);
        return page.getItems();
    }

    /**
     * 获取集合内用户可见的文件元数据（游标分页），带权限过滤与 Milvus 查询。
     *
     * @param collectionName 集合名称
     * @param cursor         上一页最后一条记录的 doc_id，首次传 null
     * @param pageSize       每页大小，<=0 时使用默认值 20
     * @return 分页结果
     */
    public CursorPage<Map<String, Object>> getUserCollectionFiles(String collectionName, String cursor, int pageSize) {
        if (collectionName == null || collectionName.isBlank()) {
            return new CursorPage<>(Collections.emptyList(), null, false);
        }
        if (pageSize <= 0)
            pageSize = 20;

        // 从 Redis 获取 ACL 权限
        List<String> fileChunkIds = milvusAclManager.getCollectionFiles(collectionName);
        if (fileChunkIds == null || fileChunkIds.isEmpty()) {
            return new CursorPage<>(Collections.emptyList(), null, false);
        }

        // 构建过滤表达式
        String expr = buildFileChunkExpr(fileChunkIds);
        if (expr == null || expr.isBlank()) {
            return new CursorPage<>(Collections.emptyList(), null, false);
        }

        // 添加游标条件：doc_id > cursor（利用 doc_id 字符串字典序实现服务端分页）
        if (cursor != null && !cursor.isEmpty()) {
            expr = "(" + expr + ") AND doc_id > '" + cursor.replace("'", "\\'") + "'";
        }

        QueryParam queryParam = QueryParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(defaultCollectionName)
                .withExpr(expr)
                .withOutFields(Arrays.asList("doc_id", "content", "metadata"))
                .withLimit((long) pageSize + 1)
                .build();

        R<QueryResults> response = milvusClient.query(queryParam);
        if (response == null || response.getStatus() != R.Status.Success.getCode() || response.getData() == null) {
            log.error("Milvus 游标分页查询失败, cursor={}, pageSize={}", cursor, pageSize);
            return new CursorPage<>(Collections.emptyList(), null, false);
        }

        List<Map<String, Object>> raw = getMetadataResultByMilvusClient(response);
        raw = milvusMetadataFilter.showFilter(raw);

        boolean hasMore = raw.size() > pageSize;
        if (hasMore)
            raw = raw.subList(0, pageSize);

        String nextCursor = hasMore && !raw.isEmpty() ? raw.getLast().get("doc_id").toString() : null;
        log.info("即将查询的 fileChunkIds: {}", fileChunkIds);
        return new CursorPage<>(raw, nextCursor, hasMore);
    }

    /**
     * 核心查询逻辑：查询 + 排序 + 过滤，返回全量列表。
     */
    private List<Map<String, Object>> queryAllCollectionFiles(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return Collections.emptyList();
        }

        List<String> fileChunkIds = milvusAclManager.getCollectionFiles(collectionName);
        if (fileChunkIds == null || fileChunkIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建查询表达式（使用 OR 连接多个文件分块条件）
        String expr = buildFileChunkExpr(fileChunkIds);
        if (expr == null || expr.isBlank()) {
            return Collections.emptyList();
        }

        // debug: 输出最终构建的查询表达式，便于排查表达式/转义问题
        log.debug("Milvus 查询表达式: {}", expr);

        QueryParam queryParam = QueryParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(defaultCollectionName)
                .withExpr(expr)
                .withOutFields(Arrays.asList("doc_id", "content", "metadata"))
                .withLimit((long) MAX_METADATA_QUERY_LIMIT)
                .build();
        R<QueryResults> response = milvusClient.query(queryParam);
        if (response == null) {
            log.error("Milvus 返回 null");
            return Collections.emptyList();
        }

        int status = response.getStatus();
        if (status != R.Status.Success.getCode()) {
            String errorMsg = "unknown error";
            if (response.getException() != null) {
                errorMsg = response.getException().getMessage();
            } else if (response.getMessage() != null) {
                errorMsg = response.getMessage();
            }
            log.error("Milvus 查询失败, status={}, error={}, expr={}", status, errorMsg, expr);
            return Collections.emptyList();
        }

        QueryResults data = response.getData();
        if (data == null) {
            log.error("QueryResults data 为 null, expr={}", expr);
            return Collections.emptyList();
        }

        List<Map<String, Object>> raw = getMetadataResultByMilvusClient(response);
        raw = sortByCreateTimeAndFileChunk(raw);
        return milvusMetadataFilter.showFilter(raw);
    }

    /**
     * 对查询原始结果排序：先按 createTime 倒序，再按 doc_id 升序
     */
    private List<Map<String, Object>> sortByCreateTimeAndFileChunk(List<Map<String, Object>> rawList) {
        if (rawList == null || rawList.isEmpty())
            return rawList;
        return rawList.stream()
                .sorted(Comparator
                        .comparing(this::extractCreateTime, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(this::extractFileIdFromDocId, Comparator.nullsLast(String::compareTo))
                        .thenComparingInt(this::extractChunkIdFromDocId))
                .collect(Collectors.toList());
    }

    // 从 doc_id 中提取 fileId（例如 "abc-000001" → "abc"）
    private String extractFileIdFromDocId(Map<String, Object> m) {
        String docId = (String) m.get("doc_id");
        if (docId == null || !docId.contains(":"))
            return null;
        return docId.substring(0, docId.lastIndexOf(":"));
    }

    // 从 doc_id 中提取 chunkId 数值（例如 "abc-000001" → 1）
    private int extractChunkIdFromDocId(Map<String, Object> m) {
        String docId = (String) m.get("doc_id");
        if (docId == null || !docId.contains(":"))
            return 0;
        try {
            return Integer.parseInt(docId.substring(docId.lastIndexOf(":") + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 从 metadata JSON 字符串中提取 createTime
     */
    private String extractCreateTime(Map<String, Object> record) {
        Object metaObj = record.get("metadata");
        if (!(metaObj instanceof String metaStr)) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(metaStr).getAsJsonObject();
            JsonElement elem = json.get("createTime"); // 注意 key 名与存入时一致
            if (elem != null && !elem.isJsonNull()) {
                return elem.getAsString();
            }
        } catch (Exception e) {
            // 解析失败忽略
            log.debug("提取 createTime 失败", e);
        }
        return null;
    }

    /**
     * 根据 fileId:chunkId 列表生成 Milvus 查询表达式。
     * 转义单引号防止注入（Milvus 字符串用单引号包围时会加倍单引号转义）。
     */
    private String buildFileChunkExpr(List<String> fileChunkIds) {
        List<String> clauses = new ArrayList<>();
        for (String fc : fileChunkIds) {
            if (fc == null || !fc.contains(":"))
                continue;
            String[] parts = fc.split(":", 2);
            String fileId = parts[0];
            String chunkId = String.format("%06d", Integer.parseInt(parts[1]));
            String docId = fileId + ":" + chunkId;
            if (clauses.size() >= MAX_OR_CLAUSES) {
                log.warn("Milvus OR 表达式达到上限 ({}), 截断至 {}. 后续分页将覆盖剩余数据", fileChunkIds.size(), MAX_OR_CLAUSES);
                break;
            }
            clauses.add(String.format("doc_id == '%s'", docId.replace("'", "\\'")));
        }
        return clauses.isEmpty() ? null : "(" + String.join(" OR ", clauses) + ")";
    }

    private void reportFetchTask(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            try {
                uploadTaskStore.node(taskId, "fetcher");
            } catch (Exception e) {
                log.debug("上报任务节点失败 taskId={}, nodeType=fetcher", taskId, e);
            }
        }
    }

    public Result<String> shareFiles(String collectionName, String fileId, Long userId, int chunkId) {
        if (userId == null || userId <= 0) {
            return Result.error(400, "userId 不能为空");
        }
        if (collectionName == null || collectionName.isBlank() || fileId == null || fileId.isBlank()) {
            return Result.error(400, "collectionName/fileId 不能为空");
        }
        int chunkSize = resolveChunkSizeFromCollection(collectionName, fileId);
        if (chunkSize <= 0) {
            return Result.error(404, "文件不存在或未入库");
        }
        ensureUserCollectionAcl(userId, collectionName);
        List<Integer> chunkIds;
        if (chunkId > 0) {
            if (chunkId > chunkSize) {
                return Result.error(400, "chunkId 超出范围");
            }
            chunkIds = List.of(chunkId);
        } else {
            int cap = (int) Math.min(chunkSize, RedisKeyConfig.MAX_CHUNK_PER_FILE);
            chunkIds = new ArrayList<>(cap);
            for (int i = 1; i <= cap; i++) {
                chunkIds.add(i);
            }
        }

        grantFileChunksToUser(userId, fileId, collectionName, chunkIds, chunkSize);
        return Result.success("分享成功");
    }

    private int resolveChunkSizeFromCollection(String collectionName, String fileId) {
        try {
            for (String entryObj : stringRedisTemplate.opsForSet()
                    .members(RedisKeyConfig.collectionFileIds(collectionName))) {
                if (entryObj == null || !entryObj.startsWith(fileId + ":"))
                    continue;
                String[] parts = entryObj.split(":", 2);
                if (parts.length == 2) {
                    return Integer.parseInt(parts[1]);
                }
            }
        } catch (Exception e) {
            log.warn("解析 chunkSize 失败 collection={} fileId={}", collectionName, fileId, e);
        }
        return 0;
    }

    private void ensureUserCollectionAcl(Long userId, String collectionName) {
        String loadKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        String unloadKey = RedisKeyConfig.userUnloadCollectionsKey(userId);
        boolean hasAcl = false;
        try {
            hasAcl = Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(loadKey, collectionName))
                    || Boolean.TRUE.equals(stringRedisTemplate.opsForSet().isMember(unloadKey, collectionName));
        } catch (Exception e) {
            log.warn("检查用户集合权限失败 userId={} collection={}", userId, collectionName, e);
        }
        if (!hasAcl) {
            stringRedisTemplate.opsForSet().add(unloadKey, collectionName);
            stringRedisTemplate.opsForValue().increment(RedisKeyConfig.collectionUserCountKey(collectionName));
        }
    }

    private void grantFileChunksToUser(Long userId, String fileId, String collectionName, List<Integer> chunkIds,
                                       int chunkSize) {
        int cap = (int) Math.min(chunkSize, RedisKeyConfig.MAX_CHUNK_PER_FILE);
        if (cap <= 0 || chunkIds == null || chunkIds.isEmpty()) {
            return;
        }
        String entry = fileId + ":" + chunkSize;
        stringRedisTemplate.opsForSet().add(RedisKeyConfig.collectionFileIds(collectionName), entry);
        stringRedisTemplate.opsForSet().add(RedisKeyConfig.fileHashKey(fileId), collectionName);
        milvusAclManager.grantFileChunksToUser(userId, fileId, collectionName, chunkIds, chunkSize);
    }

    public Result<String> updateFileChunk(String docId, String content) {
        // 删除旧数据
        String[] split = docId.split(":");
        String fileId = split[0];
        int chunkId = Integer.parseInt(split[1]);
        String collectionName = stringRedisTemplate.opsForSet()
                .randomMember(RedisKeyConfig.fileHashKey(fileId));
        deleteDocument(collectionName, chunkId, fileId);
        // 插入管道
        return Result.success();
    }

    public List<Map<String, Object>> getUserFiles() {
        List<String> allCollectionNames = milvusCollectionService.getAllCollectionNames();
        Semaphore semaphore = new Semaphore(MAX_CONCURRENT_COLLECTION_QUERIES);
        List<CompletableFuture<List<Map<String, Object>>>> futures = allCollectionNames.stream()
                .map(collectionName -> CompletableFuture.<List<Map<String, Object>>>supplyAsync(() -> {
                    try {
                        semaphore.acquire();
                        List<Map<String, Object>> files = queryAllCollectionFiles(collectionName);
                        return files.stream()
                                .map(original -> {
                                    Map<String, Object> newMap = new HashMap<>(original);
                                    newMap.put("collectionName", collectionName);
                                    return newMap;
                                }).collect(Collectors.toList());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return new ArrayList<>();
                    } finally {
                        semaphore.release();
                    }
                }, milvusExecutor)).collect(Collectors.toList());
        return futures.stream().map(CompletableFuture::join).flatMap(List::stream).collect(Collectors.toList());
    }
}