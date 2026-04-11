package com.XYai.myai.RAG.ETLpipeline;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.Config.Result;
import com.XYai.myai.RAG.ETLpipeline.Factory.PipelineDefinitionFactory;
import com.XYai.myai.RAG.ETLpipeline.Factory.UploadIngestionContextFactory;
import com.XYai.myai.RAG.ETLpipeline.Oss.OssService;
import com.XYai.myai.RAG.ETLpipeline.POJO.IngestionContext;
import com.XYai.myai.RAG.ETLpipeline.POJO.UpLoadAccumulator;
import com.XYai.myai.RAG.ETLpipeline.POJO.UploadProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 上传控制器：只负责接收文件、构造 ETL 输入、调用 IngestionEngine、汇总结果。
 */
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
    private PipelineDefinitionFactory pipelineDefinitionFactory;
    @Resource
    private UploadIngestionContextFactory uploadIngestionContextFactory;
    @Resource
    private UploadTaskStore uploadTaskStore;

    /**
     * 接收前端上传的文件列表并处理。
     * 步骤：
     * 1. 验证文件列表非空
     * 2. 逐个文件处理（上传到 OSS、构造 Document、执行 ETL 管道）
     * 3. 如果启用了 RAG（向量检索），则将所有分块加入 VectorStore
     * 4. 返回处理结果信息
     *
     * @param files 前端上传的文件列表，参数名为 "file"
     * @return Result<String> 包含处理结果的消息（成功/失败统计）
     */
    @PostMapping("up")
    public Result<String> upLoad(
            @RequestParam("file") List<MultipartFile> files,
            @RequestParam("collectionName") String collectionName,
            @RequestParam(value = "kbId", required = false) String kbId) {
        if (!uploadProperties.getUpLoadEnabled()) {
            return Result.success("上传未开启");
        }
        if (files == null || files.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }
        UpLoadAccumulator accumulator = new UpLoadAccumulator();
        // 任务ID：前端轮询当前 ETL 节点时使用
        String taskId = IdUtil.getSnowflakeNextIdStr();
        accumulator.setTaskId(taskId);
        uploadTaskStore.start(taskId);

        // 异步处理，不阻塞上传响应
        CompletableFuture.runAsync(() -> {
            try {
                for (MultipartFile file : files) {
                    processSingleFile(file, accumulator, collectionName, kbId);
                }
                uploadTaskStore.success(taskId, "上传任务已完成");
            } catch (Exception ex) {
                log.error("处理上传任务失败: {}", taskId, ex);
                uploadTaskStore.error(taskId, ex.getMessage());
            }
        });

        // 立即把任务号返回给前端，前端再通过 SSE 订阅状态
        return Result.success(taskId);
    }

    /**
     * 获取实时的 ETL 执行任务（JSON 轮询版本）。
     *
     * @param taskId 任务 ID
     * @return 当前任务快照
     */
    @GetMapping("/Task")
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


    /**
     * 外部来源入口：URL / 本地文件路径。
     */
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
            // 构建管道所需对象
            IngestionContext inputContext = uploadIngestionContextFactory.createFromSource(sourceUri, sourceType,
                    collectionName, kbId);
            var pipeline = pipelineDefinitionFactory.createSourcePipeline(sourceUri, sourceType);
            // 进行管道处理
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

    /**
     * inline 文本入口：直接把文本转成字节并走同一条 ETL 管道。
     */
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
            // 构建管道所需对象
            IngestionContext inputContext = uploadIngestionContextFactory.createInline(content, collectionName, kbId);
            var pipeline = pipelineDefinitionFactory.createInlinePipeline("inline");
            // 进行管道处理
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

    private void processSingleFile(MultipartFile file, UpLoadAccumulator accumulator, String collectionName,
                                   String kbId) {
        // 判断错误文件
        if (file == null || file.isEmpty()) {
            accumulator.getFailedFiles().add("unknown(empty)");
            return;
        }
        // 安全名
        String fileName = safeFileName(file);

        try {
            // 上传OSS
            if (uploadProperties.getOssEnabled()) {
                uploadToOss(file, accumulator, fileName);
            }
            // 构建管道所需对象
            IngestionContext inputContext = uploadIngestionContextFactory.create(file, collectionName, kbId);
            inputContext.setTaskId(accumulator.getTaskId());
            var pipeline = pipelineDefinitionFactory.createUploadPipeline(fileName, file);
            // 进行管道处理
            IngestionContext outputContext = ingestionEngine.execute(pipeline, inputContext);
            // 判断是否成功
            if (outputContext.getChunks() != null && !outputContext.getChunks().isEmpty()) {
                accumulator.getAllChunks().addAll(outputContext.getChunks());
            }
        } catch (Exception ex) {
            log.warn("处理文件失败，已跳过: {}", fileName, ex);
            accumulator.getFailedFiles().add(fileName);
        }
    }

    /**
     * 上传文件到OSS
     *
     * @param file        文件
     * @param accumulator 上传对象
     * @param fileName    文件名
     * @throws IOException 报错
     */
    private void uploadToOss(MultipartFile file, UpLoadAccumulator accumulator, String fileName) throws IOException {
        OssService ossService = ossServiceProvider.getIfAvailable();
        if (ossService == null) {
            log.warn("upload.oss.enabled=true 但未找到 OssService，跳过 OSS 上传");
        } else {
            String ossUrl = ossService.upload(file);
            if (ossUrl != null) {
                accumulator.getUploadedUrls().add(fileName + " -> " + ossUrl);
            }
        }
    }

    /**
     * 根据累积器信息构建最终返回给前端的处理结果消息。
     *
     * @param accumulator 上传处理累积器，包含已上传 URL、失败文件列表、所有分块等
     * @return Result<String> 包含处理统计信息或错误信息
     */
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

    /**
     * 设置安全名,防止空名
     *
     * @param file 上传文件
     * @return 安全文件名
     */
    private String safeFileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        return (original == null || original.isBlank()) ? "unknown" : original;
    }

}