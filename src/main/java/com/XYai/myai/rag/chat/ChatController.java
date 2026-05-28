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
import org.springframework.ai.document.Document;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.validation.Valid;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * AI 对话控制器
 * 提供流式对话、快速模式、模型健康检查等接口
 * 使用 SseEmitter 实现流式输出（非响应式）
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@Tag(name = "AI对话", description = "大模型对话相关接口")
public class ChatController {

    private static final String DEFAULT_MESSAGE = "你好";
    private static final String PROVIDER = "aliyun";
    private static final String STATUS_READY = "ready";
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

    // ==================== 普通对话接口（SSE 流式） ====================

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话(GET)", description = "GET方式的流式对话接口")
    public SseEmitter chat(
            @Parameter(description = "用户消息", example = "你好")
            @RequestParam(value = "message", defaultValue = DEFAULT_MESSAGE) String message,
            @Parameter(description = "会话ID(雪花ID)，为空则后端自动生成", example = "1829475612345678848")
            @RequestParam(value = "conversationId", required = false) Long conversationId,
            @RequestParam(value = "chatFile", required = false) String file) {
        SseEmitter emitter = new SseEmitter(0L);
        chatOrchestrator.chat(message, conversationId, file == null ? "无" : file, emitter);
        return emitter;
    }

    @PostMapping(value = "/chat", consumes = "application/json", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话(POST)", description = "POST方式的流式对话接口")
    public SseEmitter chatPost(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        chatOrchestrator.chat(request.getMessage(), request.getConversationId(),
                request.getFiles() == null ? "无" : request.getFiles(), emitter);
        return emitter;
    }

    // ==================== 快速模式接口 ====================

    @GetMapping(value = "/chat/fast", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "快速模式(GET)", description = "并发调用多个模型，取最快响应")
    public SseEmitter chatFast(
            @Parameter(description = "用户消息", example = "你好")
            @RequestParam(value = "message", defaultValue = DEFAULT_MESSAGE) String message,
            @Parameter(description = "会话ID(雪花ID)", example = "1829475612345678848")
            @RequestParam(value = "conversationId", required = false) Long conversationId,
            @RequestParam(value = "chatFile", required = false) String file) {
        log.info("快速模式 - conversationId: {}", conversationId);
        SseEmitter emitter = new SseEmitter(0L);
        chatOrchestrator.chatFast(message, conversationId, file == null ? "无" : file, emitter);
        return emitter;
    }

    @PostMapping(value = "/chat/fast", consumes = "application/json", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "快速模式(POST)", description = "POST方式的快速模式接口")
    public SseEmitter chatFastPost(@Valid @RequestBody ChatRequest request) {
        log.info("快速模式(POST) - conversationId: {}", request.getConversationId());
        SseEmitter emitter = new SseEmitter(0L);
        chatOrchestrator.chatFast(request.getMessage(), request.getConversationId(),
                request.getFiles() == null ? "无" : request.getFiles(), emitter);
        return emitter;
    }

    @PostMapping("/chat/files")
    public Result<Map<String, String>> chatFiles(
            @RequestParam(value = "chatFile", required = false) MultipartFile file) {
        log.info("chatFiles 文件解析开始");
        if (file == null || file.isEmpty()) {
            return Result.error(1, "文件不能为空");
        }
        byte[] rawBytes;
        try {
            rawBytes = file.getBytes();
        } catch (IOException e) {
            log.error("文件读取失败", e);
            return Result.error(2, "文件读取失败，请检查文件是否损坏");
        }
        Document document = Document.builder()
                .text("")
                .metadata(new HashMap<>())
                .build();
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
        String content = context.getDocument().getText();
        if (!StringUtils.hasText(content)) {
            return Result.error(1, "未提取出文字");
        }
        int maxLength = 10_000;
        if (content.length() > maxLength) {
            content = content.substring(0, maxLength) + "…（已截断）";
        }
        Map<String, String> result = new HashMap<>();
        result.put("content", content);
        result.put("contentType", file.getContentType());
        log.info("chatFiles 文件解析完成");
        return Result.success(result);
    }
}
