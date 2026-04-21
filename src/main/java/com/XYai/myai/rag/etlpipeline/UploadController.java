package com.XYai.myai.rag.etlpipeline;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.config.Result;
import com.XYai.myai.rag.etlpipeline.Factory.PipelineDefinitionFactory;
import com.XYai.myai.rag.etlpipeline.Factory.UploadIngestionContextFactory;
import com.XYai.myai.rag.etlpipeline.Oss.OssService;
import com.XYai.myai.rag.etlpipeline.POJO.*;
import com.XYai.myai.rag.etlpipeline.UploadTaskStore;
import com.XYai.myai.rag.milvus.MilvusFileManager;
import com.XYai.myai.redis.RedisKeyConfig;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.POJO.User;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StringUtils;
import org.springframework.util.StreamUtils;
import com.XYai.myai.rag.aop.Annotation.rateLimit;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * 上传控制器：只负责接收文件、构造 ETL 输入、调用 IngestionEngine、汇总结果。
 */
@Slf4j
@RestController
@RequestMapping("/upload")
public class UploadController {

    private static final long MAX_IN_MEMORY_FILE_BYTES = 10L * 1024L * 1024L;

    @Resource
    private ObjectProvider<OssService> ossServiceProvider;
    @Resource
    private UploadProperties uploadProperties;
    @Resource
    private IngestionEngine ingestionEngine;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private PipelineDefinitionFactory pipelineDefinitionFactory;
    @Resource
    private UploadIngestionContextFactory uploadIngestionContextFactory;
    @Resource
    private UploadTaskStore uploadTaskStore;
    @Resource
    private MilvusFileManager milvusFileManager;
    @Resource(name = "uploadExecutor")
    private ThreadPoolTaskExecutor uploadExecutor;


    @PostMapping("up")
    @rateLimit(limit = 10, rateName = "upload_up", windowMs = 1000)
    public Result<String> upLoad(
            @RequestParam("file") List<MultipartFile> files,
            @RequestParam("collectionName") String collectionName) {
        if (!uploadProperties.getUpLoadEnabled()) {
            return Result.success("上传未开启");
        }
        if (files == null || files.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }
        log.info("上传开始");

        UpLoadAccumulator accumulator = new UpLoadAccumulator();
        String taskId = IdUtil.getSnowflakeNextIdStr();
        accumulator.setTaskId(taskId);
        uploadTaskStore.start(taskId);
        User user = LoginUserInfoManager.get();

        List<MultipartFile> safeFiles = new ArrayList<>(files.size());
        List<File> tempFilesToCleanup = new ArrayList<>();

        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) {
                log.warn("上传列表中存在空文件，跳过");
                continue;
            }
            long size = f.getSize();
            if (size <= 0) {
                return Result.error(400, "上传文件大小非法: " + safeFileName(f));
            }

            try {
                if (size <= MAX_IN_MEMORY_FILE_BYTES) {
                    byte[] bytes;
                    try (InputStream is = f.getInputStream()) {
                        bytes = StreamUtils.copyToByteArray(is);
                    }
                    InMemoryMultipartFile mem = new InMemoryMultipartFile(f.getName(), f.getOriginalFilename(), f.getContentType(), bytes);
                    safeFiles.add(mem);
                } else {
                    String safeName = safeFileName(f).replaceAll("[^a-zA-Z0-9_.-]", "_");
                    Path tmpPath = Files.createTempFile("upload_", "_" + safeName);
                    try (InputStream is = f.getInputStream()) {
                        Files.copy(is, tmpPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    File tmp = tmpPath.toFile();
                    tempFilesToCleanup.add(tmp);
                    FileBackedMultipartFile fb = new FileBackedMultipartFile(f.getName(), f.getOriginalFilename(), f.getContentType(), tmp);
                    safeFiles.add(fb);
                }
            } catch (IOException e) {
                for (File t : tempFilesToCleanup) {
                    try {
                        Files.deleteIfExists(t.toPath());
                    } catch (Exception ignored) {}
                }
                log.error("准备上传文件失败: {}", safeFileName(f), e);
                return Result.error(500, "准备上传文件失败: " + safeFileName(f));
            }
        }

        try {
            uploadExecutor.execute(() -> {
                long skipFile = 0L;
                try {
                    for (MultipartFile file : safeFiles) {
                        String fileHash = milvusFileManager.calculateFileHash(file);
                        SkipFileInfo skipFileInfo = milvusFileManager.generateFile(fileHash, collectionName, taskId);
                        skipFile += skipFileInfo.getSkipStatus();
                        if (skipFileInfo.getSkipStatus().equals(SkipFileInfo.UP_FILE)) {
                            boolean upStatus = processSingleFile(file, accumulator, collectionName, user, fileHash);
                            if(upStatus) {
                                redissonClient.getSet(RedisKeyConfig.fileHashKey(fileHash)).add(collectionName);
                            }
                        }
                    }
                    uploadTaskStore.success(taskId, skipFile == 0L ? "上传完成" : "上传完成，重复命中文件: " + skipFile + " 个");
                } catch (Exception ex) {
                    log.error("处理上传任务失败: {}", taskId, ex);
                    uploadTaskStore.error(taskId, ex.getMessage());
                } finally {
                    for (File t : tempFilesToCleanup) {
                        try {
                            Files.deleteIfExists(t.toPath());
                        } catch (Exception ignored) {}
                    }
                }
            });
        } catch (RejectedExecutionException rex) {
            log.warn("uploadExecutor saturated, rejecting upload task {}", taskId);
            for (File t : tempFilesToCleanup) {
                try {
                    Files.deleteIfExists(t.toPath());
                } catch (Exception ignored) {}
            }
            return Result.error(503, "服务器繁忙，请稍后重试");
        }

        return Result.success(taskId);
    }

    @PostMapping("/Task")
    public Result<UploadTaskStore.TaskState> getJob(@RequestParam("taskId") String taskId) {
        if (!StringUtils.hasText(taskId)) {
            return Result.error(400, "taskId不能为空");
        }
        UploadTaskStore.TaskState current = uploadTaskStore.get(taskId);
        if (current == null) {
            return Result.error(404, "任务不存在或已过期");
        }
        return Result.success(current);
    }

    @PostMapping("source")
    public Result<String> upLoadSource(
            @RequestParam("sourceUri") String sourceUri,
            @RequestParam(value = "sourceType", required = false, defaultValue = "file") String sourceType,
            @RequestParam("collectionName") String collectionName,
            @RequestParam(value = "kbId", required = false) String kbId) {
        if (!uploadProperties.getUpLoadEnabled()) {
            return Result.success("上传未开启");
        }
        if (!StringUtils.hasText(sourceUri)) {
            return Result.error(400, "sourceUri不能为空");
        }

        UpLoadAccumulator accumulator = new UpLoadAccumulator();
        try {
            User user = LoginUserInfoManager.get();
            IngestionContext inputContext = uploadIngestionContextFactory.createFromSource(
                    sourceUri, sourceType, collectionName, kbId, user);
            var pipeline = pipelineDefinitionFactory.createSourcePipeline(sourceUri, sourceType);
            IngestionContext outputContext = ingestionEngine.execute(pipeline, inputContext);
            if (outputContext.getChunks() != null && !outputContext.getChunks().isEmpty()) {
                accumulator.getAllChunks().addAll(outputContext.getChunks());
            }
        } catch (Exception ex) {
            log.warn("处理外部来源失败，已跳过: {}", sourceUri, ex);
            accumulator.getFailedFiles().add(sourceUri);
        }
        return buildUploadResult(accumulator);
    }

    @PostMapping("inline")
    public Result<String> upLoadInline(
            @RequestParam("content") String content,
            @RequestParam("collectionName") String collectionName,
            @RequestParam(value = "kbId", required = false) String kbId) {
        if (!uploadProperties.getUpLoadEnabled()) {
            return Result.success("上传未开启");
        }
        if (!StringUtils.hasText(content)) {
            return Result.error(400, "content不能为空");
        }

        UpLoadAccumulator accumulator = new UpLoadAccumulator();
        try {
            User user = LoginUserInfoManager.get();
            IngestionContext inputContext = uploadIngestionContextFactory.createInline(
                    content, collectionName, kbId, user);
            var pipeline = pipelineDefinitionFactory.createInlinePipeline("inline");
            IngestionContext outputContext = ingestionEngine.execute(pipeline, inputContext);
            if (outputContext.getChunks() != null && !outputContext.getChunks().isEmpty()) {
                accumulator.getAllChunks().addAll(outputContext.getChunks());
            }
        } catch (Exception ex) {
            log.warn("处理inline文本失败，已跳过", ex);
            accumulator.getFailedFiles().add("inline");
        }
        return buildUploadResult(accumulator);
    }

    private boolean processSingleFile(MultipartFile file, UpLoadAccumulator accumulator, String collectionName, User user, String fileHashId) {
        if (file == null || file.isEmpty()) {
            accumulator.getFailedFiles().add("unknown(empty)");
            return false;
        }
        String fileName = safeFileName(file);

        try {
            if (uploadProperties.getOssEnabled()) {
                uploadToOss(file, accumulator, fileName);
            }

            IngestionContext inputContext = uploadIngestionContextFactory.create(file, collectionName, user, fileHashId);
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
            accumulator.getFailedFiles().add(fileName);
            return false;
        }
    }

    private void uploadToOss(MultipartFile file, UpLoadAccumulator accumulator, String fileName) throws IOException {
        OssService ossService = ossServiceProvider.getIfAvailable();
        if (ossService == null) {
            log.warn("upload.oss.enabled=true 但未找到 OssService，跳过 OSS 上传");
            return;
        }

        final int maxAttempts = 3;
        long baseDelayMs = 200L;
        boolean success = false;
        String ossUrl = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                ossUrl = ossService.upload(file);
                success = true;
                break;
            } catch (Exception ex) {
                if (attempt == maxAttempts) {
                    log.warn("OSS 上传失败（最终尝试）: {} attempts, file={}", attempt, fileName, ex);
                    throw new IOException("OSS 上传失败: " + ex.getMessage(), ex);
                }
                long jitter = (long) (Math.random() * 100);
                long backoff = baseDelayMs * (1L << (attempt - 1)) + jitter;
                log.warn("OSS 上传失败，准备重试 (attempt={}): {}, backoff={}ms", attempt, fileName, backoff);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("OSS 上传被中断", ie);
                }
            }
        }

        if (success && ossUrl != null) {
            accumulator.getUploadedUrls().add(fileName + " -> " + ossUrl);
        }
    }

    private Result<String> buildUploadResult(UpLoadAccumulator accumulator) {
        if (accumulator.getAllChunks().isEmpty() && accumulator.getUploadedUrls().isEmpty()) {
            return Result.error(400, "没有可处理成功的文件，失败文件: " + String.join(",", accumulator.getFailedFiles()));
        }
        String msg = "处理完成: 成功文件=" + Math.max(1 - accumulator.getFailedFiles().size(), 0)
                + ", 失败文件=" + accumulator.getFailedFiles().size()
                + ", 文档分块=" + accumulator.getAllChunks().size()
                + ", OSS上传=" + accumulator.getUploadedUrls().size();
        if (!accumulator.getFailedFiles().isEmpty()) {
            msg += ", 失败列表=" + String.join(",", accumulator.getFailedFiles());
        }
        return Result.success(msg);
    }

    private String safeFileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        return (original == null || original.isBlank()) ? "unknown" : original;
    }

}