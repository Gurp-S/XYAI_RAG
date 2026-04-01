package com.XYai.myai.Controller;

import com.XYai.myai.Aop.rateLimitAspect;
import com.XYai.myai.Config.Result;
import com.XYai.myai.Oss.OssService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上传控制器，负责接收前端上传的文件，上传到 OSS（如果启用），
 * 使用 Tika 解析文件内容，并将文档分块（可选使用 spring-ai 的分块器），
 * 最终将分块后的 Document 交给 VectorStore 进行向量化存储（如果 RAG 启用）。
 */
@Slf4j
@RestController
@RequestMapping("/upload")
public class UploadController {

    private static final int DEFAULT_MAX_PARSE_CHARS = 5_000_000;

    @Resource
    private VectorStore vectorStore;

    @Resource
    private ObjectProvider<OssService> ossServiceProvider;
    @Resource
    private UploadProperties uploadProperties;
    @Resource
    private ObjectProvider<TextSplitter> textSplitterProvider;
    @Autowired
    private rateLimitAspect rateLimitAspect;

    /**
     * 接收前端上传的文件列表并处理。
     * 步骤：
     * 1. 验证文件列表非空
     * 2. 逐个文件处理（上传到 OSS、解析、分块、收集分块）
     * 3. 如果启用了 RAG（向量检索），则将所有分块加入 VectorStore
     * 4. 返回处理结果信息
     * @param files 前端上传的文件列表，参数名为 "file"
     * @return Result<String> 包含处理结果的消息（成功/失败统计）
     */
    @PostMapping("up")
    public Result<String> upLoad(@RequestParam("file") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }
        UpLoadAccumulator accumulator = new UpLoadAccumulator();
        // 单文件处理
        for (MultipartFile file : files) {
            processSingleFile(file, accumulator);
        }
        // 判断RAG开启，加入向量数据库（注意：embedding 服务或 VectorStore 可能不可用，捕获异常以避免整个请求失败）
        if (uploadProperties.ragEnabled && !accumulator.getAllChunks().isEmpty()) {
            try {
                vectorStore.add(accumulator.getAllChunks());
            } catch (Exception e) {
                // 记录警告并继续返回上传结果（向量化失败不影响文件上传本身）
                log.warn("向量存储/嵌入服务不可用，跳过向量化处理：{}", e.toString());
            }
        }
        // 设置并返回成功/失败信息
        return buildUploadResult(files.size(), accumulator);
    }

    /**
     * 单个文件的处理逻辑：
     * 1. 验证文件非空
     * 2. 根据配置决定是否上传到 OSS（uploadProperties.ossEnabled）
     * 3. 使用 Tika 解析文件内容为 Document
     * 4. 使用 spring-ai 的 TextSplitter（如果启用且可用）或回退到本地分块实现
     * 5. 将分块结果收集到 accumulator 中（供后续统一向量化或存储）
     * 注意：方法内部会将出错的文件记录到 accumulator.getFailedFiles()
     *
     * @param file 单个上传文件
     * @param accumulator 累积器，用于收集上传 URL、分块、失败文件等信息
     */
    private void processSingleFile(MultipartFile file, UpLoadAccumulator accumulator) {
        if (file == null || file.isEmpty()) {
            accumulator.getFailedFiles().add("unknown(empty)");
            return;
        }
        String fileName = safeFileName(file);
        try {
            // 1. 上传文件到 OSS（如果启用）
            if (uploadProperties.ossEnabled) {
                uploadToOss(file, accumulator, fileName);
            }
            // 2. 使用 Tika 解析文本内容
            Document tikaResult = getTypeByTika(file);

            // 3. 分块（优先使用 spring-ai 的分块器）
            List<Document> splitterResult = null;
            if (Boolean.TRUE.equals(uploadProperties.getSplitWithSpringAIEnabled())) {
                splitterResult = splitWithSpringAI(tikaResult);
            }
            // 如果 Spring AI 没有开启，或者它的分块器返回了 null，则回退到本地分块
            if (splitterResult == null || splitterResult.isEmpty()) {
                splitterResult = toSplitter(tikaResult);
            }
            // 4. 向量化处理
            if (splitterResult != null && !splitterResult.isEmpty()) {
                accumulator.getAllChunks().addAll(splitterResult);
            }

        } catch (Exception ex) {
            log.warn("处理文件失败，已跳过: {}", fileName, ex);
            // 记录失败文件名
            accumulator.getFailedFiles().add(fileName);
        }
    }

    /**
     * 上传文件到OSS
     * @param file 文件
     * @param accumulator 上传对象
     * @param fileName 文件名
     * @throws IOException
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
     * @param totalFiles 上传的总文件数量
     * @param accumulator 上传处理累积器，包含已上传 URL、失败文件列表、所有分块等
     * @return Result<String> 包含处理统计信息或错误信息
     */
    private Result<String> buildUploadResult(int totalFiles, UpLoadAccumulator accumulator) {
        if (accumulator.getAllChunks().isEmpty() && accumulator.getUploadedUrls().isEmpty()) {
            return Result.error(400, "没有可处理成功的文件，失败文件: " + String.join(",", accumulator.getFailedFiles()));
        }
        String msg = "处理完成: 成功文件=" + (totalFiles - accumulator.getFailedFiles().size())
                + ", 失败文件=" + accumulator.getFailedFiles().size()
                + ", 文档分块=" + accumulator.getAllChunks().size()
                + ", OSS上传=" + accumulator.getUploadedUrls().size();
        if (!accumulator.getFailedFiles().isEmpty()) {
            msg += ", 失败列表=" + String.join(",", accumulator.getFailedFiles());
        }
        return Result.success(msg);
    }

    /**
     * 准备待加入 VectorStore 的 Document 列表。
     * 当前实现仅返回传入的文档列表（占位），VectorStore.add 在底层会调用嵌入模型。
     * 如果需要在此处补充 metadata 或预处理，可在此扩展。
     *
     * @param docs 待向量化的文档列表
     * @return 处理后可直接传递给 VectorStore 的文档列表
     */
    private List<Document> toEmbedding(List<Document> docs) {
        // VectorStore.add(List<Document>) 会在底层调用嵌入模型，这里只补齐 metadata
        return docs;
    }

    /**
     * 使用 spring-ai 的 TextSplitter 对源文档进行分块；如果找不到可用的 TextSplitter 或调用失败，
     * 返回 null 表示需要回退到本地分块实现。
     *
     * @param sourceDoc 待分块的源 Document
     * @return 分块后的 Document 列表，若不可用则返回 null
     */
    private List<Document> splitWithSpringAI(Document sourceDoc) {
        TextSplitter splitter = textSplitterProvider.getIfAvailable();
        if (splitter == null) {
            log.warn("splitWithSpringAIEnabled=true 但未找到 TextSplitter Bean，回退本地分块");
            return null;
        }
        try {
            //分块
            List<Document> docs = splitter.split(List.of(sourceDoc));
            if (docs != null && !docs.isEmpty()) {
                return docs;
            }
            log.warn("TextSplitter.split 返回结果为空或类型不匹配，回退本地分块");
        } catch (Exception e) {
            log.warn("调用 TextSplitter 失败，回退本地分块", e);
        }
        return null;
    }

    /**
     * 本地的文本分块实现：
     * 根据 sourceDoc 的文本内容按固定 chunkSize 和 overlap 进行滑窗分块，
     * 每个分块将保留部分元数据并附带 chunkIndex、chunkStart、chunkEnd 等信息。
     *
     * @param sourceDoc 源 Document（包含文本和元数据）
     * @return 分块后的 Document 列表（若文本为空返回空列表）
     */
    private List<Document> toSplitter(Document sourceDoc) {
        Map<String, Object> baseMetadata = new HashMap<>(sourceDoc.getMetadata());

        String content = sourceDoc.getText();
        if (content == null || content.isBlank()) {
            return List.of();
        }
        // 根据文件类型设置分块大小
        int chunkSize = resolveChunkSize(baseMetadata);
        int overlap = Math.min(resolveChunkOverlap(baseMetadata), chunkSize / 2);
        // 分块文件对象
        List<Document> chunks = new ArrayList<>();
        int index = 0;
        int start = 0;
        // 用索引开始分块
        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());
            String piece = content.substring(start, end).trim();
            if (!piece.isEmpty()) {
                Map<String, Object> chunkMeta = new HashMap<>(baseMetadata);
                chunkMeta.put("chunkIndex", index);
                chunkMeta.put("chunkStart", start);
                chunkMeta.put("chunkEnd", end);
                chunks.add(new Document(piece, chunkMeta));
                index++;
            }
            if (end >= content.length()) {
                break;
            }
            start = Math.max(end - overlap, start + 1);
        }

        return chunks;
    }

    /**
     * 使用 Apache Tika 解析上传的 MultipartFile，提取纯文本并构造 Document。
     *
     * @param file 上传的 MultipartFile
     * @return 包含解析后文本和元数据的 Document
     * @throws Exception 当读取文件流或 Tika 解析失败时抛出（调用方需捕获）
     */
    private Document getTypeByTika(MultipartFile file) throws Exception {
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, safeFileName(file));
        if (file.getContentType() != null) {
            metadata.set(Metadata.CONTENT_TYPE, file.getContentType());
        }

        ContentHandler handler = new BodyContentHandler(DEFAULT_MAX_PARSE_CHARS);
        AutoDetectParser parser = new AutoDetectParser();

        try (InputStream inputStream = file.getInputStream()) {
            parser.parse(inputStream, handler, metadata, new ParseContext());
        }

        String text = handler.toString();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("文件内容解析为空: " + safeFileName(file));
        }
        // 设置文件的类型信息到元数据
        Map<String, Object> docMeta = new LinkedHashMap<>();
        docMeta.put("fileName", safeFileName(file));
        docMeta.put("fileSize", file.getSize());
        docMeta.put("fileContentType", file.getContentType());
        docMeta.put("detectedContentType", new Tika().detect(text));

        return new Document(text, docMeta);
    }

    /**
     * 根据文件的 MIME 类型或文件名后缀，返回合适地分块大小（chunkSize）。
     * 常见规则：PDF 更大，Word 次之，纯文本较小，默认中等。
     *
     * @param metadata 文档元数据，通常包含 fileContentType 与 fileName
     * @return 建议的分块大小（字符数）
     */
    private int resolveChunkSize(Map<String, Object> metadata) {
        String type = String.valueOf(metadata.getOrDefault("fileContentType", ""));
        String fileName = String.valueOf(metadata.getOrDefault("fileName", "")).toLowerCase();
        if (type.contains("pdf") || fileName.endsWith(".pdf")) {
            return 1200;
        }
        if (type.contains("word") || fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
            return 1000;
        }
        if (type.contains("plain") || fileName.endsWith(".txt") || fileName.endsWith(".md")) {
            return 800;
        }
        return 900;
    }

    /**
     * 根据文件类型返回建议的分块重叠大小（overlap）。
     *
     * @param metadata 文档元数据
     * @return 重叠字符数
     */
    private int resolveChunkOverlap(Map<String, Object> metadata) {
        String type = String.valueOf(metadata.getOrDefault("fileContentType", ""));
        if (type.contains("pdf")) {
            return 180;
        }
        if (type.contains("word")) {
            return 150;
        }
        return 120;
    }

    /**
     * 获取文件的安全文件名（防止原文件名为空导致 NPE）。
     *
     * @param file MultipartFile
     * @return 如果原始文件名为空则返回 "unknown"，否则返回原始文件名
     */
    private String safeFileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        return (original == null || original.isBlank()) ? "unknown" : original;
    }
}