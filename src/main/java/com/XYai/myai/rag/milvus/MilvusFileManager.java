package com.XYai.myai.rag.milvus;

import com.XYai.myai.config.Result;
import com.XYai.myai.rag.etlpipeline.POJO.SkipFileInfo;
import com.XYai.myai.rag.etlpipeline.UploadTaskStore;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.user.LoginUserInfoManager;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RBitSet;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MilvusFileManager {

    @Resource
    private RedissonClient redissonClient;
    @Resource
    private MilvusAclManager milvusAclManager;
    @Resource
    private UploadTaskStore uploadTaskStore;
    @Value("${spring.ai.vectorstore.milvus.collectionName:my_ai}")
    private String physicalCollectionName;
    @Value("${spring.ai.vectorstore.milvus.databaseName:my_xy}")
    private String databaseName;
    @Resource
    private MilvusServiceClient milvusClient;  // 统一使用 service client
    @Resource
    private VectorStore vectorStore;
    @Resource
    private MilvusCollectionService milvusCollectionService;
    @Resource
    private MilvusMetadataFilter milvusMetadataFilter;

    /**
     * 解析 Milvus 查询结果为 Map 列表，过滤掉 Google Gson 对象。
     */
    @NotNull
    public List<Map<String, Object>> getMetadataResultByMilvusClient(R<QueryResults> response) {
        if (response == null || response.getData() == null) return List.of();

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
     */
    public SkipFileInfo generateFile(String fileHash, String collectionName, String taskId) {
        try {
            SkipFileInfo result = new SkipFileInfo();
            reportFetchTask(taskId);

            long userId = LoginUserInfoManager.get().getId();
            String redisKey = RedisKeyConfig.fileHashKey(fileHash);

            // 1. 检查文件哈希是否已存在
            boolean exists = redissonClient.getKeys().countExists(redisKey) > 0;
            if (!exists) {
                result.setSkipStatus(SkipFileInfo.UP_FILE);
                return result;
            }

            // 2. 获取文件块大小（从 Redis Hash 中直接读取）
            long chunkSize = getFileChunkSize(fileHash);

            // 3. 同一用户、同一集合、同一文件的情况
            if (redissonClient.getSet(redisKey).contains(collectionName) &&
                    milvusCollectionService.getAllCollectionNames().contains(collectionName) &&
                    milvusAclManager.getFileAcl(fileHash)) {

                RBitSet userChunkBits = redissonClient.getBitSet(RedisKeyConfig.userFileBitKey(userId, fileHash));
                if (userChunkBits.cardinality() == chunkSize) {
                    result.setSkipStatus(SkipFileInfo.SKIP_FILE);
                } else {
                    // 检查缺失的分块
                    for (long i = 1; i <= chunkSize; i++) {
                        if (!userChunkBits.get(i)) {
                            boolean chunkExists = redissonClient.getKeys()
                                    .countExists(RedisKeyConfig.fileChunkUserCountKey(fileHash, i)) > 0;
                            if (chunkExists) {
                                userChunkBits.set(i, true);   // 直接给予权限
                            } else {
                                result.getCopyChunks().add(i); // 需复制
                            }
                        }
                    }
                    result.setSkipStatus(result.getCopyChunks().isEmpty() ?
                            SkipFileInfo.SKIP_FILE : SkipFileInfo.COPY_CHUNK);
                }
                return result;
            }

            // 4. 不同集合或新用户：复制整个文件
            milvusAclManager.addFileUserACl(fileHash, collectionName, chunkSize);
            redissonClient.getSet(redisKey).add(collectionName);
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
    private long getFileChunkSize(String fileHash) {
        // 建议改为使用 Redisson RMap，这里兼容原逻辑：从集合中提取
        return redissonClient.getSet(RedisKeyConfig.fileHashKey(fileHash))
                .stream()
                .findFirst()
                .map(Object::toString)
                .filter(s -> s.contains(":"))
                .map(s -> Long.parseLong(s.split(":")[1]))
                .orElse(0L);
    }

    /**
     * 向 Milvus 集合添加文档（自动向量化），保证幂等性。
     */
    public Result<String> add(String collectionName, List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return Result.error(400, "文档为空");
        }

        if (!milvusCollectionService.exists(collectionName)) {
            return Result.error(404, "集合不存在或无权限: " + collectionName);
        }

        // 规范化 metadata
        MilvusMetadataFilter.sanitizeDocuments(documents, Map.of(
                "fileId", "string",
                "chunkId", "long",
                "chunkSize", "long",
                "visibility", "lowercase",
                "createTime", "string"
        ));

        // 记录 ACL 权限
        milvusAclManager.addFileUserACl(documents, collectionName);

        // 使用 VectorStore 添加（若已存在相同 ID 会自动覆盖，取决于 VectorStore 实现）
        vectorStore.add(documents);
        log.info("成功添加 {} 个文档到集合 {} ", documents.size(), collectionName);
        return Result.success("添加成功");
    }

    /**
     * 删除文件分块权限（实际向量数据保留，由 Milvus 生命周期管理）。
     */
    public void deleteDocument(Long chunkId, String fileId) {
        milvusAclManager.deleteDocumentAcl(chunkId, fileId);
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
     * 获取集合内用户可见的文件元数据，带权限过滤与 Milvus 查询。
     */
    public List<Map<String, Object>> getUserCollectionFiles(String collectionName) {
        if (collectionName == null || collectionName.isBlank()) {
            return Collections.emptyList();
        }

        List<String> fileChunkIds = milvusAclManager.getCollectionFiles(collectionName);
        if (fileChunkIds == null || fileChunkIds.isEmpty()) {
            return Collections.emptyList();
        }

        // 构建查询表达式（使用 OR 连接多个文件分块条件）
        String expr =  buildFileChunkExpr(fileChunkIds);
        if (expr == null || expr.isBlank()) {
            return Collections.emptyList();
        }

        // debug: 输出最终构建的查询表达式，便于排查表达式/转义问题
        log.debug("Milvus 查询表达式: {}", expr);

        QueryParam queryParam = QueryParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(physicalCollectionName)
                .withExpr(expr)
                .withOutFields(Arrays.asList("doc_id", "content", "metadata"))
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
        return milvusMetadataFilter.showFilter(raw);
    }

    /**
     * 根据 fileId:chunkId 列表生成 Milvus 查询表达式。
     * 转义单引号防止注入（Milvus 字符串用单引号包围时会加倍单引号转义）。
     */
    private String buildFileChunkExpr(List<String> fileChunkIds) {
        List<String> clauses = new ArrayList<>();
        for (String fc : fileChunkIds) {
            if (fc == null || !fc.contains(":")) continue;
            String[] parts = fc.split(":", 2);
            String fileId = parts[0];
            String chunkId = parts[1];

            // 转义单引号
            String escFid = fileId.replace("'", "\\'");
            String escCid = chunkId.replace("'", "\\'");
            clauses.add(String.format(
                    "(metadata[\"fileId\"] == '%s' AND metadata[\"chunkId\"] == %s)",
                    escFid, escCid)
            );
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
        long chunkSize = resolveChunkSizeFromCollection(collectionName, fileId);
        if (chunkSize <= 0) {
            return Result.error(404, "文件不存在或未入库");
        }
        ensureUserCollectionAcl(userId, collectionName);
        List<Long> chunkIds;
        if (chunkId > 0) {
            if (chunkId > chunkSize) {
                return Result.error(400, "chunkId 超出范围");
            }
            chunkIds = List.of((long) chunkId);
        } else {
            long cap = Math.min(chunkSize, RedisKeyConfig.MAX_CHUNK_PER_FILE);
            chunkIds = new ArrayList<>((int) cap);
            for (long i = 1; i <= cap; i++) {
                chunkIds.add(i);
            }
        }

        grantFileChunksToUser(userId, fileId, collectionName, chunkIds, chunkSize);
        return Result.success("分享成功");
    }

    private long resolveChunkSizeFromCollection(String collectionName, String fileId) {
        try {
            for (Object entryObj : redissonClient.getSet(RedisKeyConfig.collectionFileIds(collectionName))) {
                String entry = entryObj.toString();
                if (entry == null || !entry.startsWith(fileId + ":")) continue;
                String[] parts = entry.split(":", 2);
                if (parts.length == 2) {
                    return Long.parseLong(parts[1]);
                }
            }
        } catch (Exception e) {
            log.warn("解析 chunkSize 失败 collection={} fileId={}", collectionName, fileId, e);
        }
        return 0L;
    }

    private void ensureUserCollectionAcl(Long userId, String collectionName) {
        String loadKey = RedisKeyConfig.userLoadCollectionsKey(userId);
        String unloadKey = RedisKeyConfig.userUnloadCollectionsKey(userId);
        boolean hasAcl = false;
        try {
            hasAcl = redissonClient.getSet(loadKey).contains(collectionName)
                    || redissonClient.getSet(unloadKey).contains(collectionName);
        } catch (Exception e) {
            log.warn("检查用户集合权限失败 userId={} collection={}", userId, collectionName, e);
        }
        if (!hasAcl) {
            redissonClient.getSet(unloadKey).add(collectionName);
            redissonClient.getAtomicLong(RedisKeyConfig.collectionUserCountKey(collectionName)).incrementAndGet();
        }
    }

    private void grantFileChunksToUser(Long userId, String fileId, String collectionName, List<Long> chunkIds, long chunkSize) {
        int cap = (int) Math.min(chunkSize, RedisKeyConfig.MAX_CHUNK_PER_FILE);
        if (cap <= 0) {
            return;
        }
        RBitSet userBitSet = redissonClient.getBitSet(RedisKeyConfig.userFileBitKey(userId, fileId));
        RBitSet collectionBitSet = redissonClient.getBitSet(RedisKeyConfig.collectionFileChunkBitKey(collectionName, fileId));
        String entry = fileId + ":" + chunkSize;
        redissonClient.getSet(RedisKeyConfig.collectionFileIds(collectionName)).add(entry);
        redissonClient.getSet(RedisKeyConfig.fileHashKey(fileId)).add(collectionName);
        for (Long id : chunkIds) {
            if (id == null || id < 1 || id > cap) continue;
            if (!userBitSet.get(id)) {
                userBitSet.set(id, true);
                redissonClient.getAtomicLong(RedisKeyConfig.fileChunkUserCountKey(fileId, id)).incrementAndGet();
            }
            collectionBitSet.set(id, true);
        }
        log.info("分享文件权限: userId={} fileId={} chunks={}", userId, fileId, chunkIds.size());
    }
}