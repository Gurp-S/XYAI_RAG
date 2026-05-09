package com.XYai.myai.rag.chat;

import com.XYai.myai.rag.aop.annotation.RagTraceRoot;
import com.XYai.myai.rag.chat.pojo.ChatRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import javax.validation.Valid;
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

    @Resource
    private ChatOrchestrator chatOrchestrator;

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
            @RequestParam(value = "conversationId", required = false) String conversationId) {
        return doChat(message, conversationId);
    }

    @PostMapping(value = "/chat", consumes = "application/json", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式对话(POST)", description = "POST方式的流式对话接口")
    public Flux<String> chatPost(@Valid @RequestBody ChatRequest request) {
        return doChat(request.getMessage(), request.getConversationId());
    }

    // ==================== 快速模式接口 ====================

    @GetMapping(value = "/chat/fast", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "快速模式(GET)", description = "并发调用多个模型，取最快响应")
    public Flux<String> chatFast(
            @Parameter(description = "用户消息", example = "你好")
            @RequestParam(value = "message", defaultValue = DEFAULT_MESSAGE) String message,
            @Parameter(description = "会话ID", example = "session-123")
            @RequestParam(value = "conversationId", required = false) String conversationId) {
        log.info("快速模式 - conversationId: {}", conversationId);
        return chatOrchestrator.chatFast(message, conversationId)
                .onErrorResume(e -> {
                    log.error("快速模式调用失败: {}", e.getMessage());
                    return Flux.just(ERROR_SERVICE_BUSY);
                });
    }

    @PostMapping(value = "/chat/fast", consumes = "application/json", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "快速模式(POST)", description = "POST方式的快速模式接口")
    public Flux<String> chatFastPost(@Valid @RequestBody ChatRequest request) {
        log.info("快速模式(POST) - conversationId: {}", request.getConversationId());
        return chatOrchestrator.chatFast(request.getMessage(), request.getConversationId())
                .onErrorResume(e -> {
                    log.error("chatFastPost快速模式调用失败: {}", e.getMessage());
                    return Flux.just(ERROR_SERVICE_BUSY);
                });
    }

    // ==================== 私有方法 ====================

    private Flux<String> doChat(String message, String conversationId) {
        log.info("对话 - conversationId: {}, message: {}", conversationId, truncateMessage(message));
        return chatOrchestrator.chat(message, conversationId)
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