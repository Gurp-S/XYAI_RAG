package com.XYai.myai.rag.chat;

import com.XYai.myai.rag.aop.Annotation.RagTraceRoot;
import com.XYai.myai.rag.chat.Service.ChatService;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.user.POJO.User;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
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

    private final ChatModel chatModel;
    @Resource
    private ChatService chatService;

    /**
     * 返回当前聊天模型的基础状态信息，用于快速健康检查。
     *
     * @return 包含 provider、bean 与 status 的状态信息
     */
    @GetMapping("/model")
    public Map<String, String> model() {
        return Map.of(
                // "provider", "ollama",
                "provider", "aliyun-bailian",
                "bean", chatModel.getClass().getSimpleName(),
                "status", "ready");
    }

    /**
     * 通过 GET 方式发起聊天请求。
     *
     * @param message        用户输入内容，默认值为“你好”
     * @param conversationId 会话 ID，可选；为空时由服务端使用默认会话
     * @return 模型返回的文本内容
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(
            @RequestParam(value = "message", defaultValue = "你好") String message,
            @RequestParam(value = "conversationId", required = false) String conversationId) {
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
        return doChat(request.message(), request.conversationId());
    }

    /**
     * 统一聊天调用入口，转发到聊天服务实现。
     *
     * @param message        用户输入内容
     * @param conversationId 会话 ID
     * @return 模型返回的文本内容
     */
    @RagTraceRoot(name = "对话", conversationIdArg = "conversationId", taskIdArg = "taskId")
    private Flux<String> doChat(String message, String conversationId) {
        return chatService.DoChat(message, conversationId);
    }

    /**
     * 聊天请求体，包含用户输入的 message 与可选的 conversationId。
     *
     * @param message        用户的输入消息
     * @param conversationId 可选的会话 ID，用于在多轮对话中保持上下文
     */
    public record ChatRequest(String message, String conversationId, Long userId) {
    }
}
