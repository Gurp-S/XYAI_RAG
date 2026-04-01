package com.XYai.myai.Controller;

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
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/upload")
public class UploadController {

    private static final int DEFAULT_MAX_PARSE_CHARS = 5_000_000;

    @Resource
    private VectorStore vectorStore;

    @Resource
    private ObjectProvider<OssService> ossServiceProvider;

    @Value("${upload.oss.enabled:false}")
    private boolean ossEnabled;

    @Value("${upload.rag.enabled:true}")
    private boolean ragEnabled;

    @PostMapping("up")
    public Result<String> upLoad(@RequestParam("file") List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }

        UpLoadAccumulator accumulator = new UpLoadAccumulator();
        for (MultipartFile file : files) {
            processSingleFile(file, accumulator);
        }

        persistToVectorStore(accumulator.getAllChunks());
        return buildUploadResult(files.size(), accumulator);
    }

    private void processSingleFile(MultipartFile file, UpLoadAccumulator accumulator) {
        if (file == null || file.isEmpty()) {
            accumulator.getFailedFiles().add("unknown(empty)");
            return;
        }

        String fileName = safeFileName(file);
        try {
            appendUploadedUrl(file, fileName, accumulator.getUploadedUrls());
            accumulator.getAllChunks().addAll(parseAndChunk(file));
        } catch (Exception ex) {
            log.warn("处理文件失败，已跳过: {}", fileName, ex);
            accumulator.getFailedFiles().add(fileName);
        }
    }

    private void appendUploadedUrl(MultipartFile file, String fileName, List<String> uploadedUrls) throws Exception {
        String ossUrl = upFile(file);
        if (ossUrl != null) {
            uploadedUrls.add(fileName + " -> " + ossUrl);
        }
    }

    private List<Document> parseAndChunk(MultipartFile file) throws Exception {
        Document tikaResult = getTypeByTika(file);
        List<Document> splitterResult = toSplitter(tikaResult);
        return toEmbedding(splitterResult);
    }

    private void persistToVectorStore(List<Document> chunks) {
        if (ragEnabled && !chunks.isEmpty()) {
            vectorStore.add(chunks);
        }
    }

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

    private String upFile(MultipartFile file) throws Exception {
        if (!ossEnabled) {
            return null;
        }
        OssService ossService = ossServiceProvider.getIfAvailable();
        if (ossService == null) {
            log.warn("upload.oss.enabled=true 但未找到 OssService，跳过 OSS 上传");
            return null;
        }
        return ossService.upload(file);
    }

    private List<Document> toEmbedding(List<Document> docs) {
        // VectorStore.add(List<Document>) 会在底层调用嵌入模型，这里只补齐 metadata
        return docs;
    }

    private List<Document> toSplitter(Document sourceDoc) {
        Map<String, Object> baseMetadata = new HashMap<>(sourceDoc.getMetadata());

        String content = sourceDoc.getText();
        if (content == null || content.isBlank()) {
            return List.of();
        }

        int chunkSize = resolveChunkSize(baseMetadata);
        int overlap = Math.min(resolveChunkOverlap(baseMetadata), chunkSize / 2);

        List<Document> chunks = new ArrayList<>();
        int index = 0;
        int start = 0;

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

        Map<String, Object> docMeta = new LinkedHashMap<>();
        docMeta.put("fileName", safeFileName(file));
        docMeta.put("fileSize", file.getSize());
        docMeta.put("fileContentType", file.getContentType());
        docMeta.put("detectedContentType", new Tika().detect(text));

        return new Document(text, docMeta);
    }

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

    private String safeFileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        return (original == null || original.isBlank()) ? "unknown" : original;
    }
}