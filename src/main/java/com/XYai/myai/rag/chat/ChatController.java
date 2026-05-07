package com.XYai.myai.rag.chat;

import com.XYai.myai.rag.aop.Annotation.RagTraceRoot;
import com.XYai.myai.rag.chat.Service.ChatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class ChatController {

    private final ChatModel chatModel;
    private final ChatService chatService;

    @GetMapping("/model")
    public Map<String, String> model() {
        return Map.of(
                "provider", "aliyun-bailian",
                "bean", chatModel.getClass().getSimpleName(),
                "status", "ready"
        );
    }

    // 【SSE 标准流式接口】
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(
            @RequestParam(value = "message", defaultValue = "你好") String message,
            @RequestParam(value = "conversationId", required = false) String conversationId) {
        return doChat(message, conversationId);
    }

    @PostMapping(value = "/chat", consumes = "application/json", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatPost(@RequestBody ChatRequest request) {
        return doChat(request.message(), request.conversationId());
    }

    @RagTraceRoot(name = "对话", conversationIdArg = "conversationId", taskIdArg = "")
    private Flux<String> doChat(String message, String conversationId) {
        log.info("对话会话ID：{}", conversationId);
        return chatService.DoChat(message, conversationId);
    }

    public record ChatRequest(String message, String conversationId) {}
}