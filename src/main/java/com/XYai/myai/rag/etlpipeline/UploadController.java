package com.XYai.myai.rag.etlpipeline;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.commonUtils.redis.RedisKeyConfig;
import com.XYai.myai.config.Result;
import com.XYai.myai.rag.aop.annotation.RagTraceRoot;
import com.XYai.myai.rag.aop.annotation.RateLimit;
import com.XYai.myai.rag.etlpipeline.factory.PipelineDefinitionFactory;
import com.XYai.myai.rag.etlpipeline.factory.UploadIngestionContextFactory;
import com.XYai.myai.rag.etlpipeline.kafka.FileUploadEvent;
import com.XYai.myai.rag.etlpipeline.oss.OssService;
import com.XYai.myai.rag.etlpipeline.pojo.*;
import com.XYai.myai.rag.milvus.MilvusFileManager;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.pojo.User;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@Slf4j
@RestController
@RequestMapping("/upload")
public class UploadController {

    @Resource
    private ObjectProvider<OssService> ossServiceProvider;
    @Resource
    private UploadProperties uploadProperties;
    @Resource
    private IngestionEngine ingestionEngine;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private PipelineDefinitionFactory pipelineDefinitionFactory;
    @Resource
    private UploadIngestionContextFactory uploadIngestionContextFactory;
    @Resource
    private UploadTaskStore uploadTaskStore;
    @Resource
    private MilvusFileManager milvusFileManager;
    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;
    @Resource(name = "uploadExecutor")
    private TaskExecutor uploadExecutor;

    @PostMapping("up")
    @RagTraceRoot(name = "上传", conversationIdArg = "", taskIdArg = "上传")
    @RateLimit(limit = 10, rateName = "upload_up")
    public Result<String> upLoad(
            @RequestParam("file") List<MultipartFile> files,
            @RequestParam("collectionName") String collectionName) {

        // 步骤1：校验请求合法性
        Result<String> validationError = validateRequest(files, collectionName);
        if (validationError != null) {
            return validationError;
        }

        // 步骤2：初始化任务
        String taskId = initTask();

        // 步骤3：将原始文件转换为可重复读取的安全文件，同时记录跳过的空文件
        List<MultipartFile> safeFiles;
        List<File> tempFiles = new ArrayList<>();
        List<String> skippedEmptyFiles = new ArrayList<>(); // 记录空文件名
        try {
            safeFiles = prepareSafeFiles(files, tempFiles, skippedEmptyFiles);
        } catch (IOException e) {
            cleanupTempFiles(tempFiles);
            uploadTaskStore.error(taskId, "准备文件失败: " + truncateMessage(e.getMessage()));
            log.error("准备上传文件失败", e);
            return Result.error(500, "准备上传文件失败");
        }

        // 步骤4：检查是否有有效文件
        Result<String> emptyResult = checkSafeFilesNotEmpty(safeFiles, taskId, skippedEmptyFiles);
        if (emptyResult != null) {
            cleanupTempFiles(tempFiles);
            return emptyResult;
        }

        // 步骤5：提交异步处理任务
        Result<String> submitResult = submitAsyncTask(taskId, safeFiles, tempFiles, collectionName, skippedEmptyFiles);
        if (submitResult != null) {
            cleanupTempFiles(tempFiles);
            return submitResult;
        }

        return Result.success(taskId);
    }

    /**
     * 查询上传任务状态（JSON）
     */
    @GetMapping("task")
    public Result<Object> getTask(@RequestParam String taskId) {
        TaskState state = uploadTaskStore.get(taskId);
        if (state == null) {
            return Result.error(404, "任务不存在: " + taskId);
        }
        return Result.success(state);
    }

    /**
     * SSE 实时推送任务状态更新。
     * 建立连接后持续推送任务快照，直到任务完成或连接超时（10 分钟）。
     * 前端可通过 EventSource 消费。
     */
    @GetMapping(value = "task/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTask(@RequestParam String taskId) {
        SseEmitter emitter = new SseEmitter(600_000L); // 10 min timeout

        ScheduledFuture<?> future = sseScheduler.scheduleAtFixedRate(() -> {
            try {
                TaskState state = uploadTaskStore.get(taskId);
                if (state == null) {
                    emitter.send(SseEmitter.event().name("error").data("任务不存在"));
                    emitter.complete();
                    return;
                }
                emitter.send(SseEmitter.event()
                        .name("snapshot")
                        .data(JSON.toJSONString(state), MediaType.APPLICATION_JSON));

                String status = state.getStatus();
                if ("SUCCESS".equals(status) || "ERROR".equals(status)) {
                    emitter.send(SseEmitter.event().name("complete").data(status));
                    emitter.complete();
                }
            } catch (Exception e) {
                try {
                    emitter.complete();
                } catch (Exception ignored) {
                }
            }
        }, 0, 500, TimeUnit.MILLISECONDS);

        emitter.onCompletion(() -> future.cancel(false));
        emitter.onTimeout(() -> future.cancel(false));
        emitter.onError(e -> future.cancel(false));

        return emitter;
    }

    private static final ScheduledExecutorService sseScheduler = new ScheduledThreadPoolExecutor(2, r -> {
        Thread t = new Thread(r, "upload-sse-poller");
        t.setDaemon(true);
        return t;
    });

    // ==================== 步骤方法 ====================

    private Result<String> validateRequest(List<MultipartFile> files, String collectionName) {
        if (!uploadProperties.getUpLoadEnabled()) {
            return Result.success("上传未开启");
        }
        if (files == null || files.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }
        if (!StringUtils.hasText(collectionName)) {
            return Result.error(400, "collectionName 不能为空");
        }
        return null;
    }

    private String initTask() {
        String taskId = IdUtil.getSnowflakeNextIdStr();
        uploadTaskStore.start(taskId);
        log.info("上传任务已启动: {}", taskId);
        return taskId;
    }

    /**
     * 将原始文件转换为可安全重复读取的副本，同时记录被跳过的空文件名
     * 
     * @param originals         原始上传文件列表
     * @param tempFiles         生成的临时文件列表（输出参数，用于清理）
     * @param skippedEmptyFiles 被跳过的空文件名列表（输出参数）
     * @return 安全的 MultipartFile 列表
     */
    private List<MultipartFile> prepareSafeFiles(List<MultipartFile> originals,
            List<File> tempFiles,
            List<String> skippedEmptyFiles) throws IOException {
        List<MultipartFile> safeFiles = new ArrayList<>();
        for (MultipartFile f : originals) {
            if (f == null || f.isEmpty()) {
                String name = safeFileName(f);
                log.warn("上传列表中存在空文件，跳过: {}", name);
                skippedEmptyFiles.add(name);
                continue;
            }
            long size = f.getSize();
            if (size <= 0) {
                throw new IOException("上传文件大小非法: " + safeFileName(f));
            }
            if (size <= uploadProperties.getMaxInMemoryFileBytes()) {
                byte[] bytes;
                try (InputStream is = f.getInputStream()) {
                    bytes = StreamUtils.copyToByteArray(is);
                }
                InMemoryMultipartFile mem = new InMemoryMultipartFile(
                        f.getName(), f.getOriginalFilename(), f.getContentType(), bytes);
                safeFiles.add(mem);
            } else {
                String safeName = safeFileName(f).replaceAll("[^a-zA-Z0-9_.-]", "_");
                Path tmpPath = Files.createTempFile("upload_", "_" + safeName);
                try (InputStream is = f.getInputStream()) {
                    Files.copy(is, tmpPath, StandardCopyOption.REPLACE_EXISTING);
                }
                File tmp = tmpPath.toFile();
                tempFiles.add(tmp);
                FileBackedMultipartFile fb = new FileBackedMultipartFile(
                        f.getName(), f.getOriginalFilename(), f.getContentType(), tmp);
                safeFiles.add(fb);
            }
        }
        return safeFiles;
    }

    private Result<String> checkSafeFilesNotEmpty(List<MultipartFile> safeFiles, String taskId,
            List<String> skippedEmptyFiles) {
        if (safeFiles.isEmpty()) {
            String msg = "没有有效的文件可上传";
            if (!skippedEmptyFiles.isEmpty()) {
                msg += "，已跳过空文件: " + skippedEmptyFiles.size() + " 个";
            }
            uploadTaskStore.error(taskId, msg);
            return Result.error(400, msg);
        }
        return null;
    }

    private Result<String> submitAsyncTask(String taskId, List<MultipartFile> safeFiles,
            List<File> tempFiles, String collectionName,
            List<String> skippedEmptyFiles) {
        User user = LoginUserInfoManager.getUser();
        try {
            uploadExecutor.execute(() -> processFilesInAsync(taskId, safeFiles, tempFiles,
                    collectionName, user, skippedEmptyFiles));
        } catch (RejectedExecutionException rex) {
            uploadTaskStore.error(taskId, "服务器繁忙");
            log.warn("uploadExecutor 饱和，拒绝上传任务: {}", taskId);
            return Result.error(503, "服务器繁忙，请稍后重试");
        }
        return null;
    }

    // ==================== 异步核心处理 ====================

    private void processFilesInAsync(String taskId, List<MultipartFile> safeFiles,
            List<File> tempFiles, String collectionName,
            User user, List<String> skippedEmptyFiles) {
        UpLoadAccumulator accumulator = new UpLoadAccumulator();
        accumulator.setTaskId(taskId);
        int successCount = 0;
        List<String> failedFiles = new ArrayList<>();

        try {
            for (MultipartFile file : safeFiles) {
                String fileHash;
                try {
                    fileHash = milvusFileManager.calculateFileHash(file);
                } catch (Exception e) {
                    log.warn("计算文件哈希失败，跳过: {}", safeFileName(file), e);
                    failedFiles.add(safeFileName(file) + "(哈希失败)");
                    continue;
                }

                SkipFileInfo skipFileInfo = milvusFileManager.generateFile(fileHash, collectionName, taskId);
                boolean fileSuccess = false;
                try {
                    if (skipFileInfo.getSkipStatus() == SkipFileInfo.UP_FILE) {
                        log.info("进行文件上传: {}", safeFileName(file));
                        fileSuccess = processSingleFile(file, accumulator, collectionName, user, fileHash, List.of());
                    } else if (skipFileInfo.getSkipStatus() == SkipFileInfo.UP_CHUNK) {
                        log.info("进行分块复用: {}", safeFileName(file));
                        fileSuccess = processSingleFile(file, accumulator, collectionName, user, fileHash,
                                skipFileInfo.getUpChunks());
                    } else {
                        log.info("跳过文件 结果:{}", skipFileInfo.getSkipStatus());
                    }
                    if (fileSuccess) {
                        stringRedisTemplate.opsForSet().add(RedisKeyConfig.fileHashKey(fileHash), collectionName);
                        successCount++;
                    } else {
                        failedFiles.add(safeFileName(file));
                    }
                } catch (Exception e) {
                    log.warn("处理文件失败: {}", safeFileName(file), e);
                    failedFiles.add(safeFileName(file));
                }
            }

            String detailMsg = buildDetailMessage(successCount, failedFiles, accumulator, skippedEmptyFiles);
            uploadTaskStore.success(taskId, detailMsg);

        } catch (Exception ex) {
            // 即使中途异常退出，也保留已处理的统计信息
            log.error("处理上传任务中断: {}", taskId, ex);
            String partialMsg = buildDetailMessage(successCount, failedFiles, accumulator, skippedEmptyFiles)
                    + "（任务中断: " + truncateMessage(ex.getMessage()) + "）";
            uploadTaskStore.error(taskId, partialMsg);
        } finally {
            cleanupTempFiles(tempFiles);
        }
    }

    /**
     * 处理单个文件，OSS 失败不阻断主流程。返回值表示是否成功生成分块。
     */
    private boolean processSingleFile(MultipartFile file, UpLoadAccumulator accumulator,
            String collectionName, User user, String fileHashId,
            List<Integer> copyChunks) {
        if (file == null || file.isEmpty()) {
            return false;
        }
        String fileName = safeFileName(file);
        try {
            String taskId = IdUtil.getSnowflakeNextId() + "";
            FileUploadEvent fileUploadEvent = new FileUploadEvent(
                    taskId,file.getBytes(),fileName,collectionName,
                    user.getId(), UUID.randomUUID().toString()
            );
            kafkaTemplate.send("etl-file",fileHashId,fileUploadEvent);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        try {
            // OSS 上传（失败仅记录，不中断后续处理）
            if (uploadProperties.getOssEnabled()) {
                try {
                    uploadToOss(file, accumulator, fileName);
                } catch (Exception ossEx) {
                    log.warn("OSS 上传失败 (不影响知识库处理): {}", fileName, ossEx);
                    accumulator.getOssFailedFiles().add(fileName);
                }
            }

            IngestionContext inputContext = uploadIngestionContextFactory.create(
                    file, collectionName, user, fileHashId, copyChunks);
            inputContext.setTaskId(accumulator.getTaskId());
            var pipeline = pipelineDefinitionFactory.createUploadPipeline(fileName, file);
            IngestionContext outputContext = ingestionEngine.execute(pipeline, inputContext);

            if (outputContext.getChunks() != null && !outputContext.getChunks().isEmpty()) {
                accumulator.getAllChunks().addAll(outputContext.getChunks());
                return true;
            }
            return false;
        } catch (Exception ex) {
            log.warn("处理文件失败，已跳过: {}", fileName, ex);
            return false;
        }
    }

    // ==================== 辅助方法 ====================

    private void uploadToOss(MultipartFile file, UpLoadAccumulator accumulator, String fileName) throws IOException {
        OssService ossService = ossServiceProvider.getIfAvailable();
        if (ossService == null) {
            log.warn("OSS 服务不可用，跳过上传");
            return;
        }
        final int maxAttempts = 3;
        long baseDelayMs = 200L;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String ossUrl = ossService.upload(file);
                accumulator.getUploadedUrls().add(fileName + " -> " + ossUrl);
                return;
            } catch (Exception ex) {
                if (attempt == maxAttempts) {
                    throw new IOException("OSS 上传失败: " + ex.getMessage(), ex);
                }
                long jitter = (long) (Math.random() * 100);
                long backoff = baseDelayMs * (1L << (attempt - 1)) + jitter;
                log.warn("OSS 上传失败，重试 {}/{}: {}", attempt, maxAttempts, fileName);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("OSS 上传被中断", ie);
                }
            }
        }
    }

    private String buildDetailMessage(int successCount, List<String> failedFiles,
            UpLoadAccumulator accumulator, List<String> skippedEmptyFiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("成功文件: ").append(successCount);
        if (!failedFiles.isEmpty()) {
            sb.append(", 失败文件: ").append(failedFiles.size())
                    .append(" (").append(String.join(", ", failedFiles)).append(")");
        }
        if (!accumulator.getOssFailedFiles().isEmpty()) {
            sb.append(", OSS上传失败: ").append(accumulator.getOssFailedFiles().size())
                    .append(" (").append(String.join(", ", accumulator.getOssFailedFiles())).append(")");
        }
        sb.append(", 文档分块: ").append(accumulator.getAllChunks().size());
        sb.append(", OSS上传成功: ").append(accumulator.getUploadedUrls().size());
        if (!skippedEmptyFiles.isEmpty()) {
            sb.append(", 跳过空文件: ").append(skippedEmptyFiles.size());
        }
        return sb.toString();
    }

    private String safeFileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        return (original == null || original.isBlank()) ? "unknown" : original;
    }

    private void cleanupTempFiles(List<File> tempFiles) {
        for (File f : tempFiles) {
            try {
                Files.deleteIfExists(f.toPath());
            } catch (Exception ignored) {
            }
        }
    }

    private String truncateMessage(String msg) {
        if (msg == null)
            return "未知错误";
        int maxLen = uploadProperties.getMaxErrorMessageLength();
        return msg.length() > maxLen ? msg.substring(0, maxLen) + "..." : msg;
    }
}