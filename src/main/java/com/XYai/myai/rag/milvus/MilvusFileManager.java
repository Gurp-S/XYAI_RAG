package com.XYai.myai.rag.milvus;

import com.XYai.myai.mapper.FileRecordMapper;
import com.XYai.myai.rag.etlpipeline.POJO.SkipFileInfo;
import com.XYai.myai.rag.etlpipeline.UploadTaskStore;
import com.XYai.myai.rag.milvus.POJO.FilePermission;
import com.XYai.myai.rag.milvus.POJO.FileRecord;
import com.XYai.myai.redis.RedisKeyConfig;
import com.alibaba.fastjson2.JSON;
// ...existing code...
import io.milvus.client.MilvusClient;
import io.milvus.grpc.QueryResults;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.QueryParam;
import io.milvus.response.QueryResultsWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class MilvusFileManager {

    private static volatile Method insertFieldFactoryMethod;

    @Resource
    private RedissonClient redissonClient;
    @Resource
    private UploadTaskStore uploadTaskStore;
    @Resource
    private FileRecordMapper fileRecordMapper;
    @Resource
    private MilvusAclManager milvusAclManager;
    @Resource
    private MilvusClient milvusClient;
    @Value("${spring.ai.vectorstore.milvus.databaseName:default}")
    private String databaseName;

    /**
     * 文件去重判断入口
     */
    public SkipFileInfo generateFile(MultipartFile file, String collectionName, String taskId) {
        try {
            SkipFileInfo result = new SkipFileInfo();
            reportFetchTask(taskId);

            // 1. 计算文件哈希
            String fileHash = calculateFileHash(file);
            result.setFileHashId(fileHash);

            // 2. 构建文件记录
            String fileName = safeFileName(file);
            FileRecord newRecord = buildFileRecord(fileHash, fileName, collectionName);
            String redisKey = RedisKeyConfig.fileHashKey(fileHash);

            // 3. 检查是否已存在
            FileRecord existing = getExistingFileRecord(redisKey, newRecord);

            // 4. 不存在则按新文件处理
            if (existing == null) {
                //插入redis
                milvusAclManager.addFileUserACl(fileHash ,collectionName);
                //插入DB
                fileRecordMapper.insert(newRecord);
                result.setSkipStatus(SkipFileInfo.UP_FILE);
                return result;
            }

            // 5. 已存在：判断集合是否相同
            if (Objects.equals(existing.getCollectionName(), collectionName)) {
                log.info("collectionName:{} 已存在相同文件，跳过上传", collectionName);
                result.setSkipStatus(SkipFileInfo.SKIP_FILE);
                return result;
            }

            // 6. 跨集合复制向量
            reportTaskCopy(taskId, existing.getCollectionName(), collectionName);
            SkipFileInfo copyResult = copyToNewCollection(collectionName, existing.getCollectionName(), existing.getFileId(), result);
            if (!Objects.equals(copyResult.getSkipStatus(), SkipFileInfo.COPY_FILE)) {
                return copyResult;
            }

            // 7. 复制后更新集合信息
            saveFileHashId(collectionName, existing.getFileId());//集合下文件

            return copyResult;
        } catch (IOException e) {
            throw new RuntimeException("提取文件字节失败", e);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("计算文件哈希失败", e);
        }
    }

    /**
     * 计算文件SHA256
     */
    private String calculateFileHash(MultipartFile file) throws IOException, NoSuchAlgorithmException {
        try (InputStream in = file.getInputStream()) {
            return sha256Hex(in);
        }
    }

    /**
     * 构建文件记录
     */
    private FileRecord buildFileRecord(String fileId, String fileName, String collectionName) {
        return FileRecord.builder()
                .fileId(fileId)
                .fileName(fileName)
                .collectionName(collectionName)
                .createTime(LocalDateTime.now())
                .build();
    }

    /**
     * 获取已存在的文件记录（Redis + DB）
     */
    private FileRecord getExistingFileRecord(String redisKey, FileRecord newRecord) {
        // redis检验 (权限文件名)
        RBucket<String> filePermission = redissonClient.getBucket(redisKey);
        String permissionJson = filePermission.get();
        FilePermission permission = (permissionJson == null || permissionJson.isBlank())
                ? null
                : JSON.parseObject(permissionJson, FilePermission.class);
        // 存在返回
        if (permission != null) {
            return toFileRecord(permission, newRecord.getFileId());
        }

        // 缓存不存在查DB
        FileRecord existing = fileRecordMapper.selectById(newRecord.getFileId());
        // DB存在
        if (existing != null) {
            return existing;
        }
        // Redis DB都不存在第一次遇到文件
        return null;
    }

    /**
     * 跨集合复制向量数据
     */
    private SkipFileInfo copyToNewCollection(String targetCollection, String sourceCollection,
                                             String fileId, SkipFileInfo result) {
        // 查询源集合
        R<QueryResults> queryRsp = milvusClient.query(
                QueryParam.newBuilder()
                        .withDatabaseName(databaseName)
                        .withCollectionName(sourceCollection)
                        .withExpr(String.format("metadata[\"fileId\"] == \"%s\"", fileId))
                        .withOutFields(List.of("*"))
                        .build()
        );

        if (queryRsp == null || queryRsp.getStatus() != R.Status.Success.getCode() || queryRsp.getData() == null) {
            log.error("查询源集合失败");
            result.setSkipStatus(SkipFileInfo.SKIP_ERROR);
            return result;
        }

        // 转换结构
        QueryResultsWrapper wrapper = new QueryResultsWrapper(queryRsp.getData());
        if (wrapper.getRowRecords().isEmpty()) {
            log.warn("源集合无可复制向量: sourceCollection={}, fileId={}", sourceCollection, fileId);
            result.setSkipStatus(SkipFileInfo.SKIP_ERROR);
            return result;
        }

        Map<String, List<Object>> columns = new LinkedHashMap<>();
        for (var row : wrapper.getRowRecords()) {
            for (var entry : row.getFieldValues().entrySet()) {
                columns.computeIfAbsent(entry.getKey(), key -> new ArrayList<>()).add(entry.getValue());
            }
        }

        // 构建插入字段
        List<InsertParam.Field> fields = new ArrayList<>();
        for (var entry : columns.entrySet()) {
            fields.add(buildInsertField(entry.getKey(), entry.getValue()));
        }

        // 复制修改权限 同时修改集合名
        fields = milvusAclManager.copyFilePermission(sourceCollection, fields);

        // 插入目标集合
        R<?> insertRsp = milvusClient.insert(InsertParam.newBuilder()
                .withDatabaseName(databaseName)
                .withCollectionName(targetCollection)
                .withFields(fields)
                .build());
        if (insertRsp == null || insertRsp.getStatus() != R.Status.Success.getCode()) {
            log.error("写入目标集合失败: targetCollection={}, fileId={}", targetCollection, fileId);
            result.setSkipStatus(SkipFileInfo.SKIP_ERROR);
            return result;
        }


        result.setSkipStatus(SkipFileInfo.COPY_FILE);
        return result;
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

    private void reportTaskCopy(String taskId, String sourceCollection, String targetCollection) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        try {
            uploadTaskStore.copy(taskId, sourceCollection, targetCollection);
        } catch (Exception e) {
            log.debug("上报任务复制状态失败 taskId={}, source={}, target={}", taskId, sourceCollection, targetCollection, e);
        }
    }

    /**
     * 反射构造Field（兼容异常）
     */
    private InsertParam.Field buildFieldWithReflection(String name, List<Object> values) {
        try {
            Method method = insertFieldFactoryMethod;
            if (method == null) {
                Class<?> fieldClass = Class.forName("io.milvus.param.dml.InsertParam$Field");
                method = fieldClass.getMethod("of", String.class, List.class);
                insertFieldFactoryMethod = method;
            }
            return (InsertParam.Field) method.invoke(null, name, values);
        } catch (Throwable t) {
            throw new RuntimeException("构造Field失败: " + name, t);
        }
    }

    private InsertParam.Field buildInsertField(String name, List<Object> values) {
        try {
            return new InsertParam.Field(name, values);
        } catch (Exception e) {
            return buildFieldWithReflection(name, values);
        }
    }

    /**
     * 统一更新复制文件所属集合（合并后 唯一入口）
     */
    public void saveFileHashId(String collectionName, String fileId) {
        String redisKey = RedisKeyConfig.fileHashKey(fileId);

        // 加分布式锁（防止并发更新导致数据错乱）
        RLock lock = redissonClient.getLock(redisKey + ":lock");
        boolean locked = false;

        try {
            // 尝试加锁，5 秒等待，10 秒自动释放
            locked = lock.tryLock(5, 10, TimeUnit.SECONDS);
            if (!locked) {
                log.warn("获取分布式锁超时，跳过更新: fileId={}", fileId);
                return;
            }

            // 先查redis获取最新记录
            FileRecord record = null;
            RBucket<String> filePermission = redissonClient.getBucket(redisKey);
            String permissionJson = filePermission.get();
            FilePermission permission = (permissionJson == null || permissionJson.isBlank())
                    ? null
                    : JSON.parseObject(permissionJson, FilePermission.class);
            if (permission != null) {
                permission.setCollectionName(collectionName);
                // 同步更新 redis 中的集合名，保证缓存与DB一致
                filePermission.set(JSON.toJSONString(permission));
                record = toFileRecord(permission, fileId);
            }

            // 没有再查DB
            if (record == null) {
                record = fileRecordMapper.selectById(fileId);
                if (record == null) {
                    log.warn("未找到文件记录，无法更新集合: fileId={}", fileId);
                    return;
                }
            }
            //更新目标复制集合

            // 更新 DB（先持久化，保证数据不丢）
            record.setCollectionName(collectionName);
            fileRecordMapper.updateById(record);
            log.debug("DB 更新成功: fileId={}, collectionName={}", fileId, collectionName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("更新文件集合时被中断: fileId={}", fileId, e);
        } catch (Exception e) {
            log.error("更新文件集合异常: fileId={}", fileId, e);
        } finally {
            // 确保锁一定被释放
            if (locked && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    log.warn("释放分布式锁异常: fileId={}", fileId, e);
                }
            }
        }
    }

    // ...existing code...

    private FileRecord toFileRecord(FilePermission permission, String defaultFileId) {
        FileRecord record = new FileRecord();
        BeanUtils.copyProperties(permission, record);
        if (record.getFileId() == null || record.getFileId().isBlank()) {
            record.setFileId(defaultFileId);
        }
        return record;
    }

    /**
     * 流式SHA256
     */
    private String sha256Hex(InputStream in) throws NoSuchAlgorithmException, IOException {
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

    /**
     * 安全文件名
     */
    private String safeFileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return (name == null || name.isBlank()) ? "unknown" : name;
    }
}