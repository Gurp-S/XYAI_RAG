package com.XYai.myai.Controller;

import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.XYai.myai.Service.ChatService;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 聊天接口控制器，负责接收 HTTP 请求并委托给聊天服务。
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class ChatController {

    @Resource
    private ChatService chatService;

    private final ChatModel chatModel;

    /**
     * 返回当前聊天模型的基础状态信息，用于快速健康检查。
     *
     * @return 包含 provider、bean 与 status 的状态信息
     */
    @GetMapping("/model")
    public Map<String, String> model() {
        return Map.of(
                "provider", "ollama",
                "bean", chatModel.getClass().getSimpleName(),
                "status", "ready"
        );
    }

    /**
     * 通过 GET 方式发起聊天请求。
     *
     * @param message 用户输入内容，默认值为“你好”
     * @param conversationId 会话 ID，可选；为空时由服务端使用默认会话
     * @return 模型返回的文本内容
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(
            @RequestParam(value = "message", defaultValue = "你好") String message,
            @RequestParam(value = "conversationId", required = false) String conversationId
    ) {
        // 如果前端传入 header userId，则直接透传，否则服务层会回退到拦截器/线程上下文
        return doChat(message, conversationId);
    }

    /**
     * 通过 POST(JSON) 方式发起聊天请求。
     *
     * @param request 聊天请求体，包含 message 与可选 conversationId
     * @return 模型返回的文本内容
     */
    @PostMapping(value = "/chat", consumes = "application/json", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatPost(@RequestBody ChatRequest request) {
        // 优先使用 header 中的 userId（如果有），否则服务层回退到 BaseContext
        return doChat(request.message(), request.conversationId());
    }

    /**
     * 统一聊天调用入口，转发到聊天服务实现。
     *
     * @param message 用户输入内容
     * @param conversationId 会话 ID
     * @return 模型返回的文本内容
     */
    private Flux<String> doChat(String message, String conversationId) {
        return chatService.DoChat(message, conversationId);
    }

    /**
     * 聊天请求体。
     */
    public record ChatRequest(String message, String conversationId) {
    }
}
