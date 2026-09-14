package com.XYai.myai.rag.etlpipeline;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.config.Result;
import com.XYai.myai.rag.aop.annotation.RagTraceRoot;
import com.XYai.myai.rag.aop.annotation.RateLimit;
import com.XYai.myai.rag.etlpipeline.oss.OssService;
import com.XYai.myai.rag.etlpipeline.pojo.*;
import com.XYai.myai.rag.kafka.event.FileUploadEvent;
import com.XYai.myai.rag.milvus.MilvusFileManager;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.pojo.User;
import com.alibaba.fastjson2.JSON;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Slf4j
@RestController
@RequestMapping("/upload")
public class UploadController {

    private static final ScheduledExecutorService sseScheduler = new ScheduledThreadPoolExecutor(2, r -> {
        Thread t = new Thread(r, "upload-sse-poller");
        t.setDaemon(true);
        return t;
    });
    @Resource
    private ObjectProvider<OssService> ossServiceProvider;
    @Resource
    private UploadProperties uploadProperties;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private UploadTaskStore uploadTaskStore;
    @Resource
    private MilvusFileManager milvusFileManager;
    @Resource
    private KafkaTemplate<String, Object> kafkaTemplate;

    @PostMapping("up")
    @RagTraceRoot(name = "上传", conversationIdArg = "", taskIdArg = "上传")
    @RateLimit(limit = 120, rateName = "upload_up", windowMs = 60_000)
    public Result<String> upLoad(
            @RequestParam("file") List<MultipartFile> files,
            @RequestParam("collectionName") String collectionName) {
        log.info("upLoad up");
        // 步骤1：校验请求合法性
        Result<String> validationError = validateRequest(files, collectionName);
        if (validationError != null) {
            return validationError;
        }
        // 步骤2：初始化任务
        String taskId = IdUtil.getSnowflakeNextIdStr();
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
        log.info("开始调用processFilesInAsync: taskId={}", taskId);
        try {
            processFilesInAsync(taskId,safeFiles,tempFiles,collectionName,skippedEmptyFiles);
            log.info("processFilesInAsync调用完成: taskId={}", taskId);
        } catch (Exception e) {
            log.error("processFilesInAsync抛出异常: taskId={}", taskId, e);
            throw e;
        }

        log.info("返回taskId给客户端: taskId={}", taskId);
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
            // 空文件判断
            if (f == null || f.isEmpty()) {
                String name = safeFileName(f);
                log.warn("上传列表中存在空文件，跳过: {}", name);
                skippedEmptyFiles.add(name);
                continue;
            }
            // 文件大小判断
            long size = f.getSize();
            if (size <= 0) {
                throw new IOException("上传文件大小非法: " + safeFileName(f));
            }
            // 文件内存缓存阈值（超过则写临时文件）
            if (size <= uploadProperties.getMaxInMemoryFileBytes()) {
                // 文件小就存内存 进行文件处理逻辑封装
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

    // ==================== 异步核心处理 ====================

    private void processFilesInAsync(String taskId, List<MultipartFile> safeFiles,
                                     List<File> tempFiles, String collectionName, List<String> skippedEmptyFiles) {
        User user = LoginUserInfoManager.getUser();
        log.info("开始异步处理: taskId={}, fileCount={}, user={}", taskId, safeFiles.size(), user != null ? user.getId() : "null");
        int successCount = 0;
        List<String> failedFiles = new ArrayList<>();
        for (MultipartFile file : safeFiles) {
            try {
                // 文件hash唯一标识
                log.info("处理文件: name={}, size={}", file.getOriginalFilename(), file.getSize());
                String fileHash = milvusFileManager.calculateFileHash(file);
                log.info("计算hash完成: fileHash={}", fileHash);
                // 重复文件判断
                SkipFileInfo skipFileInfo = milvusFileManager.generateFile(fileHash, collectionName, taskId);
                log.info("查重完成: skipStatus={}, upChunks={}", skipFileInfo.getSkipStatus(), skipFileInfo.getUpChunks());
                if(skipFileInfo.getSkipStatus()==SkipFileInfo.SKIP_FILE) {
                    log.info("文件已存在,跳过: fileHash={}", fileHash);
                    continue;
                }
                String fileName = file.getName();
                String originalFilename = file.getOriginalFilename();
                String contentType = file.getContentType();
                Long fileSize = file.getSize();
                List<Integer> upChunks = skipFileInfo.getUpChunks()==null?List.of():skipFileInfo.getUpChunks();
                byte[] fileBytes = null;
                String tempFilePath = null;
                if (file instanceof InMemoryMultipartFile mem) {
                    fileBytes = mem.getBytes();
                    log.info("文件类型: InMemoryMultipartFile, size={}bytes", fileBytes != null ? fileBytes.length : 0);
                } else if (file instanceof FileBackedMultipartFile fb) {
                    File backingFile = fb.getFile();
                    tempFilePath = backingFile == null ? null : backingFile.getAbsolutePath();
                    log.info("文件类型: FileBackedMultipartFile, tempPath={}", tempFilePath);
                } else {
                    fileBytes = file.getBytes();
                    log.info("文件类型: 原始MultipartFile, size={}bytes", fileBytes != null ? fileBytes.length : 0);
                }
                // Kafka 消息构建
                FileUploadEvent fileUploadEvent = FileUploadEvent.builder()
                        .eventId(UUID.randomUUID().toString())
                        .taskId(taskId)
                        .fileName(fileName)
                        .originalFilename(originalFilename)
                        .contentType(contentType)
                        .fileBytes(fileBytes)
                        .tempFilePath(tempFilePath)
                        .fileSize(fileSize)
                        .fileHash(fileHash)
                        .collectionName(collectionName)
                        .userId(user.getId())
                        .upChunks(upChunks)
                        .build();
                // 发送消息（同步等待 ack：丢失该消息 = 整个文件永远不会被索引）
                log.info("准备发送Kafka消息: topic=etl-file, key={}, eventId={}", fileHash, fileUploadEvent.getEventId());
                try {
                    kafkaTemplate.send("etl-file", fileHash, fileUploadEvent)
                            .get(30, java.util.concurrent.TimeUnit.SECONDS);
                    log.info("Kafka消息发送成功: topic=etl-file, key={}", fileHash);
                } catch (Exception e) {
                    log.error("Kafka消息发送失败: topic=etl-file, key={}", fileHash, e);
                    throw new RuntimeException("Kafka发送失败", e);
                }

                successCount++;
                log.info("文件处理完成: name={}, fileHash={}", originalFilename, fileHash);
            } catch (IOException | NoSuchAlgorithmException e) {
                failedFiles.add(file.getName());
                log.error("文件处理异常: name={}", file.getName(), e);
                throw new RuntimeException(e);
            }
        }
        log.info("异步处理完成: taskId={}, successCount={}, failedCount={}", taskId, successCount, failedFiles.size());
    }

    // ==================== 辅助方法 ====================

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