package com.XYai.myai.rag.chat.Service;

import com.XYai.myai.rag.aop.Annotation.RagTraceNode;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

public interface LLMService {


    @RagTraceNode(name = "llm-chat-routing", type = "LLM_ROUTING")
    String chat();

    @RagTraceNode(name = "llm-stream-routing", type = "LLM_ROUTING")
    Flux<String> streamChat();

    List<Map<String, Object>> rerank(String query, List<String> documents);
}
