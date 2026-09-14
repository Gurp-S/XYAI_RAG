package com.XYai.myai.rag.etlpipeline.consumers;

import com.XYai.myai.exception.DocumentParseException;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.config.TikaConfig;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.exception.TikaException;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.ToXMLContentHandler;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 增强版文档解析节点。
 * 支持安全解析、流式处理、智能格式识别与结构化 Markdown 输出。
 */
@Slf4j
@Component
public class Parser {

    // 纯文本 MIME 白名单，直接按 UTF-8 读取
    private static final Set<String> TEXT_MIME_TYPES = Set.of(
            "text/plain", "text/html", "text/xml", "text/csv", "text/markdown",
            "application/json", "application/xml", "text/rtf", "text/css",
            "text/tab-separated-values", "text/javascript", "text/yaml", "text/x-python"
    );

    // 最大文件大小（50MB）
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    // 纯文本最大读取字节数（10MB）
    private static final long MAX_TEXT_READ_BYTES = 10L * 1024 * 1024;

    // 线程安全的 TikaConfig
    private final TikaConfig tikaConfig;

    public Parser() throws Exception {
        // 构建安全配置（禁用外部实体等）
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        this.tikaConfig = new TikaConfig();
    }

    /**
     * 核心解析入口
     * @param file MultipartFile 上传文件
     * @return 转换后的 Markdown 字符串
     */
    @RagTraceNode(name = "解析", type = "上传管道", taskIdArg = "etlNode")
    public Document execute(MultipartFile file,String fileHash) {
        log.info("parser start ");
        // 1. 基础校验
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("文件大小不能超过50MB");
        }

        // 2. 流式解析
        try (InputStream inputStream = file.getInputStream();
             TikaInputStream tikaInputStream = TikaInputStream.get(inputStream)) {

            // 3. MIME 类型检测
            Detector detector = new DefaultDetector();
            Metadata metadata = new Metadata();
            metadata.set(Metadata.CONTENT_TYPE, file.getContentType());
            MediaType mediaType = detector.detect(tikaInputStream, metadata);
            String mime = mediaType.toString();
            log.debug("检测到文件 MIME 类型: {}", mime);

            // 4. 根据 MIME 分流处理
            String text;
            if (TEXT_MIME_TYPES.contains(mime)) {
                text = handlePlainText(tikaInputStream);
            } else {
                text = handleWithTika(tikaInputStream, mime);
            }
            return getResult(file, fileHash, text);
        } catch (DocumentParseException e) {
            throw e;
        } catch (IOException e) {
            throw new DocumentParseException("文件读取失败", e);
        } catch (Exception e) {
            throw new DocumentParseException("文档解析异常", e);
        }
    }

    @NotNull
    private static Document getResult(MultipartFile file, String fileHash, String text) {
        Map<String,Object> meta = new HashMap<>();
        // 注意：getName() 返回表单字段名（如 "file"），必须用 getOriginalFilename()
        String originalName = file.getOriginalFilename();
        meta.put("fileName", originalName == null || originalName.isBlank() ? file.getName() : originalName);
        meta.put("createTime", LocalDateTime.now().toString());
        meta.put("visibility", "private");
        return Document.builder()
                .text(text)
                .id(fileHash)
                .metadata(meta)
                .build();
    }

    /**
     * 处理纯文本文件：直接解码为字符串。
     */
    private String handlePlainText(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int bytesRead;
        long totalRead = 0;
        while ((bytesRead = inputStream.read(chunk)) != -1) {
            totalRead += bytesRead;
            if (totalRead > MAX_TEXT_READ_BYTES) {
                throw new IOException("纯文本文件超过最大读取限制（10MB）");
            }
            buffer.write(chunk, 0, bytesRead);
        }
        String text = buffer.toString(StandardCharsets.UTF_8);
        return StringUtils.hasText(text) ? text : "";
    }
    /**
     * 使用 Tika 解析复杂文档
     */
    private String handleWithTika(InputStream inputStream, String mime) throws Exception {
        // 构建安全解析器
        AutoDetectParser parser = new AutoDetectParser(tikaConfig);
        ParseContext context = new ParseContext();
        // 设置安全的 SAXParserFactory（复用在上下文中）
        SAXParserFactory safeFactory = getSafeSAXParserFactory();
        context.set(SAXParserFactory.class, safeFactory);

        Metadata metadata = new Metadata();
        ToXMLContentHandler handler = new ToXMLContentHandler();

        try {
            parser.parse(inputStream, handler, metadata, context);
        } catch (TikaException | SAXException e) {
            log.error("Tika 解析失败，MIME: {}", mime, e);
            throw new DocumentParseException("Tika 解析文档失败", e);
        }

        String xhtml = handler.toString();
        if (!StringUtils.hasText(xhtml)) {
            return "";
        }
        return xhtml;
    }

    /**
     * 构建安全的 SAXParserFactory，禁止外部实体与 DTD。
     */
    private SAXParserFactory getSafeSAXParserFactory() throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        return factory;
    }
}