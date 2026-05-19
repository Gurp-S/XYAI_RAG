package com.XYai.myai.rag.chat;

import com.XYai.myai.rag.RetrievalAugmentedGeneration;
import com.XYai.myai.rag.aop.annotation.RagTraceContext;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.aop.annotation.RagTraceRoot;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.chat.pojo.ChatMessage;
import com.XYai.myai.rag.chat.pojo.StreamResult;
import com.XYai.myai.rag.evaluate.service.SystemEvaluateService;
import com.XYai.myai.rag.mcp.pojo.ToolProcessorResult;
import com.XYai.myai.rag.memory.pojo.LoadSession;
import com.XYai.myai.rag.ragPojo.RAGResult;
import com.XYai.myai.rag.ragPojo.UserContext;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;
import com.XYai.myai.user.LoginUserInfoManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 聊天编排器（优化版）
 * 协调 RAG 处理和模型调用，完成完整的对话流程。
 * 优化点：
 * 1. 非阻塞等待 MCP 结果，使用 Mono.fromFuture 替代阻塞 get()
 * 2. 消除 chat/chatFast 重复代码，提取公共逻辑到 doChat()
 * 3. 合并 executeModelCall 和 executeModelCallFast 为统一方法
 * 4. MCP 异常降级保护，避免工具调用失败导致整个流程中断
 */
@Slf4j
@Service
public class ChatOrchestrator {

    @Resource
    private RetrievalAugmentedGeneration rag;

    @Resource
    private ModelInvocationService modelInvocation;

    @Resource
    private SystemEvaluateService systemEvaluateService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    @Qualifier("reactorBoundedElasticScheduler")
    private Scheduler reactorScheduler;

    // ==================== 主入口方法 ====================

    /**
     * 普通对话
     */
    @RagTraceRoot(name = "对话开始", conversationIdArg = "", taskIdArg = "chat")
    public Flux<String> chat(String message, String conversationId, String fileContent) {
        return doChat(message, conversationId, fileContent, false);
    }

    /**
     * 快速对话
     */
    @RagTraceRoot(name = "对话开始fast", conversationIdArg = "", taskIdArg = "chatFast")
    public Flux<String> chatFast(String message, String conversationId, String fileContent) {
        return doChat(message, conversationId, fileContent, true);
    }

    /**
     * 统一的对话流程入口
     *
     * @param fast true 为快速模式，日志标签不同，其余逻辑完全一致
     */
    private Flux<String> doChat(String message, String conversationId, String fileContent, boolean fast) {
        // 步骤0：参数校验
        if (message == null || message.isBlank()) {
            String mode = fast ? "[CHAT_FAST]" : "[CHAT]";
            log.warn("{}参数为空，拒绝请求", mode);
            return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有输入信息"));
        }

        String mode = fast ? "FAST" : "NORMAL";
        log.info("╔══════════════════════════════════════════════╗");
        log.info("║ [{}] 对话流程开始                        ║", mode);
        log.info("╚══════════════════════════════════════════════╝");
        log.info("[{}] message='{}', conversationId='{}', thread={}",
                mode,
                message.length() > 50 ? message.substring(0, 50) + "..." : message,
                conversationId,
                Thread.currentThread().getName());

        // 步骤1：获取用户上下文
        UserContext userCtx = rag.getUserContext();
        log.info("[{}] 用户上下文获取完成, userId={}", mode, userCtx != null ? userCtx.getUserId() : "null");

        // 生成 chatMessageId，使用 "::" 分隔，避免 conversationId 中包含 ":" 导致解析错误
        long seq = stringRedisTemplate.opsForValue().increment("xyai:chat:seq:" + conversationId);
        String chatMessageId = conversationId + "::" + String.format("%04d", seq);
        log.info("[{}] 生成 chatMessageId={}", mode, chatMessageId);

        // 步骤2-3：启动异步任务（MCP工具 + 会话记忆）
        log.info("[{}] >>> 启动异步任务: MCP工具 + 会话记忆", mode);
        LoadSession memorySession = rag.loadMemoryAsync(conversationId);
        CompletableFuture<List<ToolProcessorResult>> mcpFuture = rag.loadMCPToolsAsync(message, memorySession, conversationId, chatMessageId);
        log.info("[{}] >>> 异步任务已提交", mode);

        // 步骤4-8：同步执行RAG流程 → 非阻塞构建最终Prompt → 模型调用
        return executeRAGSync(message, conversationId, userCtx, chatMessageId, mode)
                .flatMap(intermediate -> buildFinalPromptNonBlocking(intermediate, mcpFuture, memorySession, message, userCtx, fileContent, mode))
                .flatMapMany(finalPrompt -> executeModelCall(finalPrompt, conversationId, message, userCtx, chatMessageId, fast));
    }

    // ==================== 健康检查 ====================

    public Map<String, Object> getModelsHealth() {
        return modelInvocation.getHealthStatus();
    }

    // ==================== 私有方法 ====================

    /**
     * 同步执行 RAG 核心流程（查询重写、意图识别、文档检索）
     *
     * @param mode 日志标签（NORMAL / FAST）
     */
    private Mono<RAGIntermediate> executeRAGSync(String message, String conversationId, UserContext userCtx, String chatMessageId, String mode) {
        return Mono.fromCallable(() -> {
                    log.info("[RAG_SYNC] ==== 开始同步RAG流程(步骤4-6) ==== thread={},mode:{}", Thread.currentThread().getName(),mode);
                    LoginUserInfoManager.setUserId(userCtx != null ? userCtx.getUserId() : null);
                    if (userCtx != null && userCtx.getSecurityContext() != null) {
                        SecurityContextHolder.setContext(userCtx.getSecurityContext());
                    }
                    try {
                        // 步骤4：查询重写
                        long t1 = System.currentTimeMillis();
                        log.info("[RAG_SYNC] >>> 步骤4: 查询重写");
                        RewriteResult rewritten = rag.rewriteQuery(message, conversationId, chatMessageId);
                        log.info("[RAG_SYNC] <<< 步骤4完成: rewritten={}, 耗时={}ms",
                                rewritten != null ? rewritten.getRewrittenQuery() : "null",
                                System.currentTimeMillis() - t1);

                        // 步骤5：意图识别
                        long t2 = System.currentTimeMillis();
                        log.info("[RAG_SYNC] >>> 步骤5: 实体识别");
                        Map<String, Integer> userMessageEntityFileChunkIds = rag.recognizeIntent(rewritten.getRewrittenQuery());
                        log.info("[RAG_SYNC] <<< 步骤5完成: entity数量={}, 耗时={}ms",
                                userMessageEntityFileChunkIds != null ? userMessageEntityFileChunkIds.size() : 0,
                                System.currentTimeMillis() - t2);

                        // 步骤6：文档检索
                        long t3 = System.currentTimeMillis();
                        log.info("[RAG_SYNC] >>> 步骤6: 多通道文档检索");
                        List<RetrievedChunk> retrieved = rag.retrieveDocuments(userMessageEntityFileChunkIds, rewritten,
                                conversationId, message);
                        log.info("[RAG_SYNC] <<< 步骤6完成: retrieved数量={}, 耗时={}ms",
                                retrieved != null ? retrieved.size() : 0,
                                System.currentTimeMillis() - t3);

                        return new RAGIntermediate(rewritten, retrieved);
                    } finally {
                        LoginUserInfoManager.remove();
                        SecurityContextHolder.clearContext();
                    }
                }).subscribeOn(reactorScheduler);
    }

    private Mono<String> buildFinalPromptNonBlocking(RAGIntermediate intermediate,
                                                     CompletableFuture<List<ToolProcessorResult>> mcpFuture,
                                                     LoadSession memorySession,
                                                     String originalMessage,
                                                     UserContext userCtx,
                                                     String fileContent,
                                                     String mode) {
        // ===== 1. 捕获当前线程的追踪上下文（重要！）=====
        String currentTraceId = RagTraceContext.getTraceId();
        Deque<String> currentStack = RagTraceContext.getNodeStackSnapshot();

        Mono<List<ToolProcessorResult>> mcpMono = Mono.fromFuture(mcpFuture)
                .onErrorResume(ex -> {
                    log.error("[{}] MCP 工具调用异常，降级为空列表。", mode, ex);
                    return Mono.just(Collections.emptyList());
                });
        return mcpMono.flatMap(mcpResults -> Mono.fromCallable(() -> {
            if (currentTraceId != null) {
                RagTraceContext.setTraceId(currentTraceId);
                RagTraceContext.restoreNodeStack(currentStack);
            } else {
                log.warn("[BUILD_PROMPT] 当前无追踪上下文，节点将不会被记录");
            }
            try {
                LoginUserInfoManager.setUserId(userCtx != null ? userCtx.getUserId() : null);
                if (userCtx != null && userCtx.getSecurityContext() != null) {
                    SecurityContextHolder.setContext(userCtx.getSecurityContext());
                }
                long t1 = System.currentTimeMillis();
                log.info("[BUILD_PROMPT] >>> 步骤7: 等待异步结果(MCP+记忆)并构建RAGResult");
                CompletableFuture<List<ToolProcessorResult>> completedFuture = CompletableFuture.completedFuture(mcpResults);
                // 这里调用带有 @RagTraceNode 的方法，切面能获取到 traceId 了
                RAGResult ragResult = rag.buildRAGResult(
                        completedFuture,
                        memorySession,
                        intermediate.rewritten(),
                        intermediate.retrieved(),
                        originalMessage);
                log.info("[BUILD_PROMPT] <<< 步骤7完成...");
                String finalPrompt = rag.formatFinalPrompt(ragResult, modelInvocation.getSystemMessage(), fileContent);
                log.info("[BUILD_PROMPT] <<< 步骤8完成...");
                return finalPrompt;
            } finally {
                LoginUserInfoManager.remove();
                SecurityContextHolder.clearContext();
                // 可选：清理当前线程的上下文，避免线程池复用污染
                RagTraceContext.clear();
            }
        }).subscribeOn(reactorScheduler));
    }

    /**
     * 统一的模型调用方法（合并原 executeModelCall 和 executeModelCallFast）
     *
     * @param fast true 为快速模式，日志输出使用相应标签
     */
    @RagTraceNode(name = "模型调用及记忆保存", type = "模型调用")
    public Flux<String> executeModelCall(String finalPrompt, String conversationId,
                                         String originalMessage, UserContext userCtx,
                                         String chatMessageId, boolean fast) {
        String mode = fast ? "FAST" : "NORMAL";
        log.info("[MODEL_CALL_{}] ==== 开始模型调用 ==== thread={}", mode, Thread.currentThread().getName());
        log.info("[MODEL_CALL_{}] finalPrompt长度={}, conversationId={}, chatMessageId={}",
                mode, finalPrompt.length(), conversationId, chatMessageId);

        StringBuffer fullAnswer = new StringBuffer();
        long startTime = System.nanoTime();
        StreamResult result = modelInvocation.callModelStream(finalPrompt, conversationId);
        String modelName = result.modelName();

        return result.content()
                .doOnNext(fullAnswer::append)
                .doOnComplete(() -> {
                    long durationMs = (System.nanoTime() - startTime) / 1_000_000;
                    log.info("[MODEL_CALL_{}] <<< 模型流式响应完成, 总字符数={}, 耗时={}ms", mode, fullAnswer.length(), durationMs);

                    // 异步保存对话记忆
                    modelInvocation.saveMemoryAsync(
                            conversationId,
                            chatMessageId,
                            originalMessage,
                            fullAnswer.toString(),
                            userCtx.getUserId());

                    // 异步记录 Token 使用量
                    modelInvocation.saveTokenUseAsync(
                            conversationId,
                            chatMessageId,
                            (long) finalPrompt.length(),
                            (long) fullAnswer.toString().length(),
                            userCtx.getUserId(),
                            durationMs,
                            modelName,
                            "chat");

                    // 异步系统评估（不阻塞主流程）
                    String answer = fullAnswer.toString();
                    if (!answer.isBlank() && !answer.contains("服务繁忙")) {
                        systemEvaluateService.submitEvaluate(
                                conversationId,
                                ChatMessage.builder()
                                        .chatMessageId(chatMessageId)
                                        .userMessage(originalMessage)
                                        .assistantMessage(answer)
                                        .userId(userCtx.getUserId())
                                        .build(),
                                List.of(),
                                durationMs,
                                originalMessage);
                    }
                })
                .doFinally(signal -> {
                    log.info("[MODEL_CALL_{}] 流结束, signal={}", mode, signal);
                    if (userCtx.getSecurityContext() != null) {
                        SecurityContextHolder.setContext(userCtx.getSecurityContext());
                    }
                })
                .onErrorResume(e -> {
                    log.error("[MODEL_CALL_{}] 模型调用失败: {}", mode, e.getMessage(), e);
                    return Flux.just("抱歉，当前服务繁忙，请稍后再试。");
                });
    }

    // ==================== 内部类 ====================

    private record RAGIntermediate(RewriteResult rewritten, List<RetrievedChunk> retrieved) {
    }
}