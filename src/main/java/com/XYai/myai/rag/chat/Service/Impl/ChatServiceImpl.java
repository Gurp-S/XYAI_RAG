package com.XYai.myai.rag.chat.Service.Impl;

import com.XYai.myai.rag.aop.Annotation.RagTraceNode;
import com.XYai.myai.rag.channel.MultiChannelRetrievalEngine;
import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.chat.POJO.ChatMessage;
import com.XYai.myai.rag.chat.Service.ChatService;
import com.XYai.myai.rag.intent.IntentResult;
import com.XYai.myai.rag.intent.POJO.SubQuestionIntent;
import com.XYai.myai.rag.memory.ConversationMemorySummaryService;
import com.XYai.myai.rag.memory.POJO.LoadSession;
import com.XYai.myai.rag.rewrite.POJO.RewriteResult;
import com.XYai.myai.rag.rewrite.QueryRewriter;
import com.XYai.myai.user.LoginUserInfoManager;
import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 聊天服务实现，基于 Ollama 模型并使用内存保存会话上下文。
 */
@Slf4j
@Service
@lombok.RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    @Resource
    private final ChatModel chatModel;
    @Resource
    private QueryRewriter queryRewriter;
    @Resource
    private ConversationMemorySummaryService conversationMemorySummaryService;
    @Resource
    private IntentResult intentResult;
    @Resource
    private MultiChannelRetrievalEngine multiChannelRetrievalEngine;


    /**
     * 处理一轮对话，按会话 ID 维护上下文并返回模型回复。
     *
     * @param message        用户输入内容
     * @param conversationId 会话 ID，可为空
     * @return 模型回复文本
     */
    @RagTraceNode(name = "对话", type = "chat")
    public Flux<String> DoChat(String message, String conversationId) {
        // 基础参数校验，避免空请求进入模型。
        if (message == null || message.isBlank()) {
            return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有输入信息"));
        }
        // RAG 对话
        // 并行加载摘要和历史记录
        LoadSession load = conversationMemorySummaryService.load(conversationId);
        // 问题重写
        RewriteResult rewrittenMessage = queryRewriter.rewrite(message, load);
        log.info("rewrittenMessage:{}", rewrittenMessage);
        // 意图识别用户消息
        List<SubQuestionIntent> questionIntents = intentResult.recognize(rewrittenMessage, load);
        log.info("questionIntents:{}", questionIntents);
        // TODO 网页检索

        // 多通道召回
        List<RetrievedChunk> retrieve = multiChannelRetrievalEngine.retrieve(questionIntents, rewrittenMessage, conversationId,message);
        log.info("retrieve:{}",retrieve);
        // prompt生成
        Prompt prompt = getPrompt(message, retrieve, load);
        // 对话
        StringBuilder fullAnswer = new StringBuilder();
        Long userId;
        var user = LoginUserInfoManager.get();
        if (user != null) {
            userId = user.getId();
        } else {
            userId = null;
        }
        SecurityContext context = SecurityContextHolder.getContext();
        return chatModel.stream(prompt)
                .map(this::extractChunkText)
                .filter(chunk -> chunk != null && !chunk.isBlank())
                .doOnNext(fullAnswer::append)
                .doFinally(signal -> {
                    SecurityContextHolder.setContext(context);
                    ChatMessage chatMessage = ChatMessage.builder()
                            .userMessage(message)
                            .assistantMessage(fullAnswer.toString())
                            .userId(userId)
                            .build();
                    conversationMemorySummaryService.compressIfNeeded(conversationId, chatMessage);
                });
    }

    @NotNull
    private Prompt getPrompt(String message, List<RetrievedChunk> retrieve, LoadSession load) {

        // 添加 MCP 工具调用的结果（如有）
        // 添加知识库检索结果
        String loadJSON = JSON.toJSONString(load);
        String retrieveJSON = JSON.toJSONString(retrieve);
        String systemMessage = """
                你是活泼且专业的 AI 助手 XY。请根据提供的文档片段和历史对话（如果有）来回答用户问题。
                重要：如果没有可用的参考文档或历史对话，不要在回答中陈述“参考文档为空”或“历史为空”等内容。
                如果有文档或历史，请仅使用必要的片段，不要逐字回显整个文档 JSON。
                参考文档:<<%s>>
                历史对话:<<%s>>
                """.formatted(retrieve == null ? "无" : retrieveJSON, load == null ? "无" : loadJSON);
        String userMessage = """
                用户消息:<<%s>>
                """.formatted(message);
        return new Prompt(
                new SystemMessage(systemMessage),
                new UserMessage(userMessage));
    }

    /**
     * 从流式响应块中提取文本内容。
     *
     * @param response 聊天模型的响应块
     * @return 提取出的文本内容，若为空则返回空字符串
     */
    private String extractChunkText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    /**
     * 启动后校验核心模型是否完成注入。
     */
    @jakarta.annotation.PostConstruct
    private void init() {
        if (this.chatModel == null) {
            log.error("ChatModel was not injected into ChatServiceIml - application context may be misconfigured");
            throw new IllegalStateException("ChatModel bean not injected into ChatServiceIml");
        }
        log.info("ChatModel injected into ChatServiceIml: {}", this.chatModel.getClass().getName());
    }
}
