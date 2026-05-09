package com.XYai.myai.rag.etlpipeline.nodes;

import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.rag.etlpipeline.pojo.NodeConfig;
import com.XYai.myai.rag.etlpipeline.pojo.NodeResult;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * 文档解析节点。
 * 利用 Apache Tika 自动检测并提取多种格式文档（如 PDF, Word 等）的正文和元数据。
 */
@Component
public class Parser implements Ingestion {

    private static final AutoDetectParser TIKA_PARSER = new AutoDetectParser();

    private static final int DEFAULT_MAX_PARSE_CHARS = 5_000_000;

    @Override
    public String getNodeType() {
        return "parser";
    }

    @RagTraceNode(name = "解析" ,type = "上传管道")
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        Document document = context.getDocument();
        if (document == null) {
            return NodeResult.fail("document is required");
        }

        // 判断是否进行
        if (StringUtils.hasText(document.getText())) {
            return NodeResult.ok("skip: rawText already exists");
        }
        // 获取元字节
        Object rawBytesValue = document.getMetadata().get(IngestionContext.META_RAW_BYTES);
        byte[] rawBytes = rawBytesValue instanceof byte[] ? (byte[]) rawBytesValue : null;
        if (rawBytes == null || rawBytes.length == 0) {
            return NodeResult.fail("rawBytes is empty");
        }
        // 检测MIME类型(如果没有)
        String text;
        // 文件小没有必要用tika
        if (rawBytes.length < 1000) {
            text = new String(rawBytes, StandardCharsets.UTF_8);
        }
        Metadata metadata = new Metadata();
        Object mimeTypeValue = document.getMetadata().get(IngestionContext.META_MIME_TYPE);
        String mimeType = mimeTypeValue == null ? null : String.valueOf(mimeTypeValue);
        if (StringUtils.hasText(mimeType)) {
            metadata.set(Metadata.CONTENT_TYPE, mimeType);
        }
        // Tika解析
        try (ByteArrayInputStream input = new ByteArrayInputStream(rawBytes)) {
            BodyContentHandler handler = new BodyContentHandler(DEFAULT_MAX_PARSE_CHARS);
            TIKA_PARSER.parse(input, handler, metadata, new ParseContext());
            text = handler.toString();
        } catch (Exception ex) {
            // 解析失败时按UTF-8直接转文本
            if (rawBytes.length < 5_000_000) {
                text = new String(rawBytes, StandardCharsets.UTF_8);
            } else {
                return NodeResult.fail("file too large and Tika failed");
            }
        }

        if (!StringUtils.hasText(text)) {
            return NodeResult.fail("parsed text is empty");
        }

        // 设置文件的类型信息到元数据
        Map<String, Object> docMeta = new HashMap<>();
        for (String name : metadata.names()) {
            docMeta.put(name, metadata.get(name));
        }
        // 写入
        context.setDocument(document.mutate().text(text).media(null).build());
        context.getDocument().getMetadata().putAll(docMeta);
        if (!StringUtils.hasText(mimeType)) {
            context.getDocument().getMetadata().put(IngestionContext.META_MIME_TYPE,
                    metadata.get(Metadata.CONTENT_TYPE));
        }
        // 返回解析长度
        return NodeResult.ok("parsed text length=" + text.length());
    }
}