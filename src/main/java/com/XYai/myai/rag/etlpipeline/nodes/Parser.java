package com.XYai.myai.rag.etlpipeline.nodes;

import com.XYai.myai.rag.aop.annotation.RagTraceContext;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.rag.etlpipeline.pojo.NodeConfig;
import com.XYai.myai.rag.etlpipeline.pojo.NodeResult;
import com.XYai.myai.rag.etlpipeline.pojo.PipelineProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.sax.WriteOutContentHandler;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * 文档解析节点。
 * 利用 Apache Tika 自动检测并提取多种格式文档（如 PDF, Word 等）的正文和元数据。
 */
@Slf4j
@Component
public class Parser implements Ingestion {

    @Resource
    private PipelineProperties pipelineProperties;
    
    // 纯文本格式白名单
    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "text/plain", "text/html", "text/xml", "text/csv", "text/markdown",
            "application/json", "application/xml", "text/rtf", "text/css",
            "text/tab-separated-values", "text/javascript"
    );

    @Override
    public String getNodeType() {
        return "parser";
    }

    @RagTraceNode(name = "解析", type = "上传管道",taskIdArg = "etlNode")
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        Document document = context.getDocument();
        if (document == null) {
            return NodeResult.fail("document is required");
        }

        // 已存在文本则跳过
        if (StringUtils.hasText(document.getText())) {
            return NodeResult.ok("skip: rawText already exists");
        }

        // 获取原始字节
        byte[] rawBytes = getRawBytes(document);
        if (rawBytes == null || rawBytes.length == 0) {
            return NodeResult.fail("rawBytes is empty");
        }

        // 小文件跳过 Tika
        if (rawBytes.length < 1000) {
            String text = new String(rawBytes, StandardCharsets.UTF_8);
            if (StringUtils.hasText(text)) {
                Document updatedDoc = document.mutate().text(text).media(null).build();
                context.setDocument(updatedDoc);
                return NodeResult.ok("small file direct decode, length=" + text.length());
            }
        }

        // 获取 MIME 类型
        String mimeType = extractMimeType(document);

        // 直接 UTF-8 解码
        if (mimeType != null && TEXT_MIME_TYPES.contains(mimeType)) {
            String text = new String(rawBytes, StandardCharsets.UTF_8);
            if (StringUtils.hasText(text)) {
                // 确保 MIME 类型存入元数据
                document.getMetadata().put(IngestionContext.META_MIME_TYPE, mimeType);
                Document updatedDoc = document.mutate().text(text).media(null).build();
                context.setDocument(updatedDoc);
                return NodeResult.ok("text mime direct decode, length=" + text.length());
            }
        }

        // 使用 Tika 解析
        String text = parseWithTika(rawBytes, mimeType, context);
        if (text == null) {
            return NodeResult.fail("parsed text is empty");
        }

        // 更新文档
        Document updatedDoc = document.mutate().text(text).media(null).build();
        context.setDocument(updatedDoc);
        return NodeResult.ok("parsed text length=" + text.length());
    }

    private byte[] getRawBytes(Document document) {
        Object rawBytesValue = document.getMetadata().get(IngestionContext.META_RAW_BYTES);
        return rawBytesValue instanceof byte[] ? (byte[]) rawBytesValue : null;
    }

    private String extractMimeType(Document document) {
        Object mimeTypeValue = document.getMetadata().get(IngestionContext.META_MIME_TYPE);
        return mimeTypeValue == null ? null : String.valueOf(mimeTypeValue);
    }

    private String parseWithTika(byte[] rawBytes, String mimeType, IngestionContext context) {
        Metadata metadata = new Metadata();
        if (StringUtils.hasText(mimeType)) {
            metadata.set(Metadata.CONTENT_TYPE, mimeType);
        }

        ParseContext parseContext = new ParseContext();

        try (ByteArrayInputStream input = new ByteArrayInputStream(rawBytes)) {
            WriteOutContentHandler handler = new WriteOutContentHandler(10000000);
            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(input, handler, metadata, parseContext);
            String text = handler.toString();
            if (StringUtils.hasText(text)) {
                // 直接将 Tika 提取的元数据写入文档
                Map<String, Object> docMeta = context.getDocument().getMetadata();
                for (String name : metadata.names()) {
                    docMeta.put(name, metadata.get(name));
                }
                // 如果之前没有 MIME 类型m使用 Tika 检测到的
                if (!StringUtils.hasText(mimeType) && metadata.get(Metadata.CONTENT_TYPE) != null) {
                    docMeta.put(IngestionContext.META_MIME_TYPE, metadata.get(Metadata.CONTENT_TYPE));
                }
                return text;
            }
        } catch (Exception ex) {
            // Tika 解析失败直接解码
            if (rawBytes.length < 5_000_000) {
                String fallbackText = new String(rawBytes, StandardCharsets.UTF_8);
                if (StringUtils.hasText(fallbackText)) {
                    return fallbackText;
                }
            }
            log.warn("Tika parse failed for mime={}, error={}", mimeType, ex.getMessage());
            RagTraceContext.setNodeWarn("Tika解析长度受限: " + ex.getMessage());
        }
        return null;
    }
}