package com.XYai.myai.rag.chat;

import com.XYai.myai.config.Result;
import com.XYai.myai.rag.chat.pojo.ChatRequest;
import com.XYai.myai.rag.etlpipeline.nodes.Parser;
import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.rag.etlpipeline.pojo.NodeResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import javax.validation.Valid;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * AI 对话控制器
 * 提供流式对话、快速模式、模型健康检查等接口
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@Tag(name = "AI对话", description = "大模型对话相关接口")
public class ChatController {

    // 常量定义
    private static final String DEFAULT_MESSAGE = "你好";
    private static final String PROVIDER = "aliyun";
    private static final String STATUS_READY = "ready";
    private static final String ERROR_SERVICE_BUSY = "抱歉，当前服务繁忙，请稍后再试。";
    private static final Tika tika = new Tika();
    @Resource
    private ChatOrchestrator chatOrchestrator;
    @Resource
    private Parser parser;

    // ==================== 健康检查接口 ====================

    @GetMapping("/model")
    @Operation(summary = "模型状态", description = "查看当前模型服务状态")
    public Map<String, String> model() {
        return Map.of(
                "provider", PROVIDER,
                "status", STATUS_READY,
                "timestamp", String.valueOf(System.currentTimeMillis())
        );
    }

    @GetMapping("/health/models")
    @Operation(summary = "模型健康状态", description = "查看所有模型的路由状态和熔断器状态")
    public Map<String, Object> getModelsHealth() {
        return chatOrchestrator.getModelsHealth();
    }

    // ==================== 普通对话接口 ====================


    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话(GET)", description = "GET方式的流式对话接口")
    public Flux<String> chat(
            @Parameter(description = "用户消息", example = "你好")
            @RequestParam(value = "message", defaultValue = DEFAULT_MESSAGE) String message,
            @Parameter(description = "会话ID", example = "session-123")
            @RequestParam(value = "conversationId") String conversationId,
            @RequestParam(value = "chatFile", required = false) String file) {
        return doChat(message, conversationId, file==null?"无":file);
    }

    @PostMapping(value = "/chat", consumes = "application/json", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话(POST)", description = "POST方式的流式对话接口")
    public Flux<String> chatPost(@Valid @RequestBody ChatRequest request) {
        return doChat(request.getMessage(), request.getConversationId(), request.getFiles()==null?"无":request.getFiles());
    }

    // ==================== 快速模式接口 ====================

    @GetMapping(value = "/chat/fast", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "快速模式(GET)", description = "并发调用多个模型，取最快响应")
    public Flux<String> chatFast(
            @Parameter(description = "用户消息", example = "你好")
            @RequestParam(value = "message", defaultValue = DEFAULT_MESSAGE) String message,
            @Parameter(description = "会话ID", example = "session-123")
            @RequestParam(value = "conversationId") String conversationId,
            @RequestParam(value = "chatFile", required = false) String file) {
        log.info("快速模式 - conversationId: {}", conversationId);
        return chatOrchestrator.chatFast(message, conversationId, file==null?"无":file)
                .onErrorResume(e -> {
                    log.error("快速模式调用失败: {}", e.getMessage());
                    return Flux.just(ERROR_SERVICE_BUSY);
                });
    }

    @PostMapping(value = "/chat/fast", consumes = "application/json", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "快速模式(POST)", description = "POST方式的快速模式接口")
    public Flux<String> chatFastPost(@Valid @RequestBody ChatRequest request) {
        log.info("快速模式(POST) - conversationId: {}", request.getConversationId());
        return chatOrchestrator.chatFast(request.getMessage(), request.getConversationId(), request.getFiles()==null?"无":request.getFiles())
                .onErrorResume(e -> {
                    log.error("chatFastPost快速模式调用失败: {}", e.getMessage());
                    return Flux.just(ERROR_SERVICE_BUSY);
                });
    }

    @PostMapping("/chat/files")
    public Result<Map<String, String>> chatFiles(
            @RequestParam(value = "chatFile", required = false) MultipartFile file) {
        log.info("chatFiles 文件解析开始");
        // 1. 参数校验
        if (file == null || file.isEmpty()) {
            return Result.error(1, "文件不能为空");
        }
        // 2. 读取文件字节
        byte[] rawBytes;
        try {
            rawBytes = file.getBytes();
        } catch (IOException e) {
            log.error("文件读取失败", e);
            return Result.error(2, "文件读取失败，请检查文件是否损坏");
        }
        // 3. 构造 IngestionContext 并调用 Parser
        Document document = Document.builder()
                .text("")
                .metadata(new HashMap<>())
                .build();
        // 设置原始字节和 MIME 类型（如果有）
        document.getMetadata().put(IngestionContext.META_RAW_BYTES, rawBytes);
        if (file.getContentType() != null) {
            document.getMetadata().put(IngestionContext.META_MIME_TYPE, file.getContentType());
        }
        IngestionContext context = IngestionContext.builder()
                .document(document)
                .build();
        NodeResult nodeResult = parser.execute(context, null);
        if (!nodeResult.isSuccess()) {
            log.error("文件解析失败: {}", nodeResult.getMessage());
            return Result.error(3, "文件解析失败：" + nodeResult.getMessage());
        }
        // 4. 获取解析后的文本
        String content = context.getDocument().getText();
        if (!StringUtils.hasText(content)) {
            return Result.error(1, "未提取出文字");
        }
        // 5. 限制长度
        int maxLength = 10_000;
        if (content.length() > maxLength) {
            content = content.substring(0, maxLength) + "…（已截断）";
        }
        // 6. 返回结果
        Map<String, String> result = new HashMap<>();
        result.put("content", content);
        result.put("contentType", file.getContentType());
        log.info("chatFiles 文件解析完成");
        return Result.success(result);
    }

    // ==================== 私有方法 ====================

    private Flux<String> doChat(String message, String conversationId, String files) {
        log.info("对话 - conversationId: {}, message: {}", conversationId, truncateMessage(message));
        return chatOrchestrator.chat(message, conversationId, files)
                .onErrorResume(e -> {
                    log.error("对话调用失败: {}", e.getMessage());
                    return Flux.just(ERROR_SERVICE_BUSY);
                });
    }

    /**
     * 截断过长的消息用于日志
     */
    private String truncateMessage(String message) {
        if (message == null) return "null";
        return message.length() > 50 ? message.substring(0, 50) + "..." : message;
    }
}