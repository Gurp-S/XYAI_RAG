package com.XYai.myai.rag.milvus;

import com.XYai.myai.config.Result;
import com.XYai.myai.rag.etlpipeline.POJO.SkipFileInfo;
import com.XYai.myai.rag.etlpipeline.UploadTaskStore;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.user.LoginUserInfoManager;
import io.milvus.client.MilvusClient;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import jakarta.annotation.Resource;
import kotlin.Metadata;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RBitSet;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

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
    @Value("${spring.ai.vectorstore.milvus.databaseName:default}")
    private String databaseName;
    @Resource
    private MilvusClient milvusClient;
    @Resource
    private VectorStore vectorStore;
    @Resource
    private MilvusCollectionService milvusCollectionService;
    @Resource
    private MilvusMetadataFilter milvusMetadataFilter;

    @Autowired
    public MilvusFileManager(MilvusServiceClient milvusClient) {
        this.milvusClient = milvusClient;
    }

    /**
     * 解析结果
     */
    @NotNull
    private static List<Map<String, Object>> getMetadataResultByMilvusClient(R<QueryResults> response) {
        QueryResultsWrapper wrapper = new QueryResultsWrapper(response.getData());
        List<QueryResultsWrapper.RowRecord> rowRecords = wrapper.getRowRecords();
        List<Map<String, Object>> list = new ArrayList<>();
        for (QueryResultsWrapper.RowRecord rowRecord : rowRecords) {
            //取消googleJSON
            Map<String, Object> original = rowRecord.getFieldValues();
            Map<String, Object> cleanMap = new HashMap<>();
            original.forEach((key, value) -> {
                if (value != null && value.getClass().getName().contains("google.gson")) {
                    cleanMap.put(key, value.toString());
                } else {
                    cleanMap.put(key, value);
                }
            });
            list.add(cleanMap);
        }
        return list;
    }

    /**
     * 文件去重判断入口
     */
    public SkipFileInfo generateFile(String fileHash, String collectionName, String taskId) {
        try {
            SkipFileInfo result = new SkipFileInfo();
            reportFetchTask(taskId);
            // 1. 构建文件记录查找
            long userId = LoginUserInfoManager.get().getId();
            String redisKey = RedisKeyConfig.fileHashKey(fileHash);
            // 2. 文件是否存在
            boolean existing = redissonClient.getKeys().countExists(redisKey) > 0;
            // 不存在 -> 上传文件
            if (!existing) {
                redissonClient.getSet(redisKey).add(collectionName);
                result.skipStatus = SkipFileInfo.UP_FILE;
                return result;
            }
            // 从redis中的人任意集合查出分了几块  fileId -> collectionNames -> collectionName -> fileId:chunkSize
            long chunkSize = redissonClient.getSet(redisKey).stream().findFirst()
                    .map(fileHashCollectionName -> redissonClient.getSet(RedisKeyConfig.collectionFileIds(fileHashCollectionName.toString())))
                    .flatMap(set -> set.stream().findFirst())
                    .map(Object::toString)
                    .filter(s -> s.contains(":"))
                    .map(s -> Long.valueOf(s.split(":")[1]))
                    .orElse(0L);
            // 存在 -> 同一用户，同一集合，同一文件 -> 跳过
            if (redissonClient.getSet(redisKey).contains(collectionName) &&//文件存在当前集合
                    milvusCollectionService.getAllCollectionNames().contains(collectionName) &&//当前集合属于用户
                        milvusAclManager.getFileAcl(fileHash)) {//当前文件属于当前用户
                // 判断用户拥有的分块数==集合分块总数
                RBitSet userFileChunkCount = redissonClient.getBitSet(RedisKeyConfig.userFileBitKey(userId, fileHash));
                // 不缺分块跳过
                if(userFileChunkCount.size() == chunkSize) {
                    result.skipStatus = SkipFileInfo.SKIP_FILE;
                    return result;
                }else{// 缺补分块
                    for (long i = 1; i <= chunkSize; i++) {
                        if(!userFileChunkCount.get(i)){
                            userFileChunkCount.set(i, true);
                        }
                    }
                }
            }
            // 存在 -> 不知道用户,同文件，不同集合 OR 同一用户，不同集合，同一文件 -> 复制整个文件
            // 集合添加分块条目格式 fileId:chunkSize 存入user:fileId
            milvusAclManager.addFileUserACl(fileHash,collectionName,chunkSize);
            result.skipStatus = SkipFileInfo.COPY_FILE;
            return result;
        } catch (Exception e) {
            throw new RuntimeException("检查文件状态失败", e);
        }
    }

    /**
     * 向指定 Milvus 集合添加文档（自动向量化）
     *
     * @param collectionName 目标集合名
     * @param documents      文档列表（Spring AI Document 对象）
     * @return 执行结果
     */
    public Result<String> add(String collectionName, List<Document> documents) {
        // 空值判断
        if (documents == null || documents.isEmpty()) {
            return Result.error(400, "文档为空");
        }
        // 确保集合存在用户的权限集合中
        Boolean existsCollectionAcl = milvusCollectionService.exists(collectionName);
        log.info("milvusCollectionService:添加文件,当前集合:{}", collectionName);
        if (existsCollectionAcl) {//添加到默认集合
            // 首先记录 ACL（依赖 Document.metadata 中的 fileId/chunkId），
            milvusAclManager.addFileUserACl(documents, collectionName);
            // 再将文档交给 VectorStore 进行 embedding & 写入 Milvus
            vectorStore.add(documents);
            log.info("milvusCollectionService:添加文件成功,当前集合:{}", collectionName);
        }
        return Result.success("添加成功");
    }

    public void deleteDocument(Long chunkId, String fileId) {
        // 删除权限
        milvusAclManager.deleteDocumentAcl(chunkId, fileId);
    }

    /**
     * 流式计算文件SHA256
     */
    public String calculateFileHash(MultipartFile file) throws IOException, NoSuchAlgorithmException {
        try (InputStream in = file.getInputStream()) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) {
                digest.update(buffer, 0, len);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }

    private void reportFetchTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        try {
            uploadTaskStore.node(taskId, "fetcher");
        } catch (Exception e) {
            log.debug("上报任务节点失败 taskId={}, nodeType=fetcher", taskId, e);
        }
    }

    /**
     * 获取集合内的用户数据（模拟查看 metadata）
     *
     * @param collectionName 集合名
     * @return 每一行记录的 Map 列表
     */
    public List<Map<String, Object>> getUserCollectionNameMetadata(String collectionName) {
        if (collectionName == null || collectionName.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // 权限过滤：获取当前用户可读的 fileId:chunkId 列表
        List<String> fileChunkIds = milvusAclManager.getCollectionMetadata(collectionName);
        if (fileChunkIds == null || fileChunkIds.isEmpty()) {
            return Collections.emptyList();
        }
        // 构建 Milvus 查询表达式
        List<String> clauses = fileChunkIds.stream()
                .filter(fc -> fc != null && !fc.isBlank())
                .filter(fc -> fc.contains(":"))
                .map(fc -> {
                    try {
                        String[] parts = fc.split(":", 2);
                        String fileId = parts[0];
                        String chunkId = parts[1];
                        // 转义
                        String escFid = fileId.replace("\"", "\\\"").replace("'", "''");
                        String escCid = chunkId.replace("\"", "\\\"").replace("'", "''");
                        // 构建
                        return String.format(
                                "(metadata[\"fileId\"] == \"%s\" AND (metadata['chunkId'] == '%s' OR metadata['chunkId'] == %s))",
                                escFid, escCid, escCid
                        );
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
        if (clauses.isEmpty()) {
            return Collections.emptyList();
        }

        // 最终表达式
        String expr = "(" + String.join(" OR ", clauses) + ")";
        QueryParam queryParam = QueryParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(physicalCollectionName)
                .withExpr(expr)
                .withOutFields(Arrays.asList("doc_id", "content", "metadata"))
                .withLimit(16384L)
                .build();
        R<QueryResults> response = milvusClient.query(queryParam);
        if (response == null || response.getStatus() != R.Status.Success.getCode() || response.getData() == null) {
            String message = response == null ? "response is null" : response.getMessage();
            log.warn("查询集合数据空或失败, collection={}, reason={}", collectionName, message);
            return Collections.emptyList();
        }
        // 解析
        List<Map<String, Object>> metadataResultByMilvusClient = getMetadataResultByMilvusClient(response);
        // 过滤返回
        return milvusMetadataFilter.showFilter(metadataResultByMilvusClient);
    }
}