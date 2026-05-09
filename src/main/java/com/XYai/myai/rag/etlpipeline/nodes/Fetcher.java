package com.XYai.myai.rag.etlpipeline.nodes;

import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.rag.etlpipeline.pojo.NodeConfig;
import com.XYai.myai.rag.etlpipeline.pojo.NodeResult;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// node/Fetcher.java
@Component
public class Fetcher implements Ingestion {

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Override
    public String getNodeType() {
        return "fetcher";
    }

    @RagTraceNode(name = "分析" ,type = "上传管道")
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        Document document = context.getDocument();
        if (document == null) {
            return NodeResult.fail("没有文件");
        }

        // 1. 幂等性校验：如果已有原始字节，直接跳过（避免重复获取）
        Object rawBytesValue = document.getMetadata().get(IngestionContext.META_RAW_BYTES);
        if (rawBytesValue instanceof byte[] rawBytes && rawBytes.length > 0) {
            return NodeResult.ok("skip: rawBytes already exists");
        }

        // 2. 获取文档来源信息（从上下文获取，由前端传入）
        String sourceUri = toStringOrNull(document.getMetadata().get(IngestionContext.META_SOURCE_URI));
        String sourceType = toStringOrNull(document.getMetadata().get(IngestionContext.META_SOURCE_TYPE));
        if (!StringUtils.hasText(sourceUri)) {
            return NodeResult.fail("source.uri is required");
        }

        // 3. 策略模式：根据来源类型选择对应的Fetcher实现
        String type = normalizeType(sourceType, sourceUri);//规范网页类型名
        try {
            return switch (type) {
                case "http", "https", "web" -> fetchHttp(document, sourceUri);
                case "inline", "text" -> fetchInline(document, sourceUri);
                default -> fetchFile(document, sourceUri);
            };
        } catch (Exception e) {
            return NodeResult.fail("fetch error: " + e.getMessage());
        }
    }

    private String toStringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String normalizeType(String type, String uri) {
        if (StringUtils.hasText(type)) {
            return type.trim().toLowerCase();
        }
        return uri.startsWith("http://") || uri.startsWith("https://") ? "http" : "file";
    }

    private NodeResult fetchFile(Document document, String uri) throws IOException {
        //文件地址
        Path path = Path.of(uri);
        //提取的原始字节
        byte[] bytes = Files.readAllBytes(path);
        document.getMetadata().put(IngestionContext.META_RAW_BYTES, bytes);
        //文档MIME类型
        document.getMetadata().put(IngestionContext.META_MIME_TYPE, Files.probeContentType(path));
        return NodeResult.ok("fetched bytes=" + bytes.length);
    }

    private NodeResult fetchHttp(Document document, String uri) throws IOException, InterruptedException {
        //向网页请求
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).GET().build();
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 400) {
            return NodeResult.fail("http status=" + response.statusCode());
        }
        //构建返回结果
        document.getMetadata().put(IngestionContext.META_RAW_BYTES, response.body());
        document.getMetadata().put(IngestionContext.META_MIME_TYPE, response.headers().firstValue("content-type").orElse(null));
        return NodeResult.ok("fetched bytes=" + response.body().length);
    }

    private NodeResult fetchInline(Document document, String content) {
        document.getMetadata().put(IngestionContext.META_RAW_BYTES, content.getBytes(StandardCharsets.UTF_8));
        document.getMetadata().put(IngestionContext.META_MIME_TYPE, "text/plain");
        return NodeResult.ok("inline text loaded");
    }
}