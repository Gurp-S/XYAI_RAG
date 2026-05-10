package com.XYai.myai.rag.chat;

import com.XYai.myai.rag.RetrievalAugmentedGeneration;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.aop.annotation.RagTraceRoot;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.intent.pojo.SubQuestionIntent;
import com.XYai.myai.rag.mcp.pojo.ToolProcessorResult;
import com.XYai.myai.rag.memory.pojo.LoadSession;
import com.XYai.myai.rag.ragPojo.RAGResult;
import com.XYai.myai.rag.ragPojo.UserContext;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;
import com.XYai.myai.user.LoginUserInfoManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 聊天编排器
 * 协调RAG处理和模型调用，完成完整的对话流程
 */
@Slf4j
@Service
public class ChatOrchestrator {

    @Resource
    private RetrievalAugmentedGeneration rag;

    @Resource
    private ModelInvocationService modelInvocation;

    @Resource
    @Qualifier("reactorBoundedElasticScheduler")
    private Scheduler reactorScheduler;

    // ==================== 主入口方法 ====================

    /**
     * 普通对话（自动降级）
     */
    @RagTraceRoot(name = "对话开始", conversationIdArg = "", taskIdArg = "chat")
    public Flux<String> chat(String message, String conversationId) {
        // 步骤0：参数校验
        if (message == null || message.isBlank()) {
            log.warn("[CHAT]参数为空，拒绝请求");
            return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有输入信息"));
        }
        log.info("╔══════════════════════════════════════════════╗");
        log.info("║ [CHAT]  ====== 对话流程开始 ======          ║");
        log.info("╚══════════════════════════════════════════════╝");
        log.info("[CHAT] message(前50字)='{}', conversationId='{}', thread={}",
                message.length() > 50 ? message.substring(0, 50) + "..." : message,
                conversationId, Thread.currentThread().getName());

        // 步骤1：获取用户上下文
        UserContext userCtx = rag.getUserContext();
        log.info("[CHAT] 用户上下文获取完成, userId={}", userCtx != null ? userCtx.getUserId() : "null");

        // 步骤2-3：启动异步任务
        log.info("[CHAT] >>> 启动异步任务: MCP工具 + 会话记忆");
        LoadSession memorySession = rag.loadMemoryAsync(conversationId);
        CompletableFuture<List<ToolProcessorResult>> mcpFuture = rag.loadMCPToolsAsync(message , memorySession);
        log.info("[CHAT] >>> 异步任务已提交, mcpFuture.isDone={}, memoryFuture.isDone, thread={}",
                mcpFuture.isDone(), Thread.currentThread().getName());

        // 步骤4-8：同步执行RAG流程，然后等待异步结果，最后调用模型
        return executeRAGSync(message, conversationId, userCtx)
                .flatMap(intermediate -> buildFinalPromptAsync(intermediate, mcpFuture, memorySession, message, userCtx))
                .flatMapMany(finalPrompt -> executeModelCall(finalPrompt, conversationId, message, userCtx));
    }

    /**
     * 快速对话（并发调用多个模型）
     */
    @RagTraceRoot(name = "对话开始fast", conversationIdArg = "", taskIdArg = "chatFast")
    public Flux<String> chatFast(String message, String conversationId) {
        if (message == null || message.isBlank()) {
            log.warn("[CHAT_FAST]参数为空，拒绝请求");
            return Flux.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有输入信息"));
        }
        log.info("╔══════════════════════════════════════════════╗");
        log.info("║ [CHAT_FAST]  ====== 快速对话流程开始 ======  ║");
        log.info("╚══════════════════════════════════════════════╝");
        log.info("[CHAT_FAST] message='{}', conversationId='{}', thread={}",
                message.length() > 50 ? message.substring(0, 50) + "..." : message,
                conversationId, Thread.currentThread().getName());

        UserContext userCtx = rag.getUserContext();
        log.info("[CHAT_FAST] 用户上下文获取完成, userId={}", userCtx != null ? userCtx.getUserId() : "null");
        log.info("[CHAT_FAST] >>> 启动异步任务: MCP工具 + 会话记忆");

        LoadSession memorySession = rag.loadMemoryAsync(conversationId);
        CompletableFuture<List<ToolProcessorResult>> mcpFuture = rag.loadMCPToolsAsync(message,memorySession);

        return executeRAGSync(message, conversationId, userCtx)
                .flatMap(intermediate -> buildFinalPromptAsync(intermediate, mcpFuture, memorySession, message, userCtx))
                .flatMapMany(finalPrompt -> executeModelCallFast(finalPrompt, conversationId, message, userCtx));
    }

    // ==================== 健康检查 ====================

    public Map<String, Object> getModelsHealth() {
        return modelInvocation.getHealthStatus();
    }

    // ==================== 私有方法 ====================

    /**
     * 同步执行RAG核心流程（步骤4-6）
     */
    private Mono<RAGIntermediate> executeRAGSync(String message, String conversationId, UserContext userCtx) {
        return Mono.<RAGIntermediate>fromCallable(() -> {
            log.info("[RAG_SYNC] ==== 开始同步RAG流程(步骤4-6) ==== thread={}", Thread.currentThread().getName());
            LoginUserInfoManager.setUserId(userCtx != null ? userCtx.getUserId() : null);
            if (userCtx != null && userCtx.getSecurityContext() != null) {
                SecurityContextHolder.setContext(userCtx.getSecurityContext());
            }
            try {
                // 步骤4：查询重写
                long t1 = System.currentTimeMillis();
                log.info("[RAG_SYNC] >>> 步骤4: 查询重写");
                RewriteResult rewritten = rag.rewriteQuery(message);
                log.info("[RAG_SYNC] <<< 步骤4完成: rewritten={}, 耗时={}ms",
                        rewritten != null ? rewritten.getRewrittenQuery() : "null",
                        System.currentTimeMillis() - t1);
                log.info("重写结果:{}",rewritten);
                // 步骤5：意图识别
                long t2 = System.currentTimeMillis();
                log.info("[RAG_SYNC] >>> 步骤5: 意图识别");
                List<SubQuestionIntent> intents = rag.recognizeIntent(rewritten);
                log.info("[RAG_SYNC] <<< 步骤5完成: intents数量={}, 耗时={}ms",
                        intents != null ? intents.size() : 0,
                        System.currentTimeMillis() - t2);
                log.info("意图识别:{}",intents);
                // 步骤6：文档检索
                long t3 = System.currentTimeMillis();
                log.info("[RAG_SYNC] >>> 步骤6: 多通道文档检索");
                List<RetrievedChunk> retrieved = rag.retrieveDocuments(intents, rewritten, conversationId, message);
                log.info("召回的文档:{}",retrieved);
                log.info("[RAG_SYNC] <<< 步骤6完成: retrieved数量={}, 耗时={}ms",
                        retrieved != null ? retrieved.size() : 0,
                        System.currentTimeMillis() - t3);
                return new RAGIntermediate(rewritten, retrieved);
            } finally {
                LoginUserInfoManager.remove();
            }
        })
                .subscribeOn(reactorScheduler);
    }

    /**
     * 构建最终Prompt（步骤7-8）
     */
    private Mono<String> buildFinalPromptAsync(RAGIntermediate intermediate,
            CompletableFuture<List<ToolProcessorResult>> mcpFuture,
            LoadSession memorySession,
            String originalMessage,
            UserContext userCtx) {
        return Mono.fromCallable(() -> {
            LoginUserInfoManager.setUserId(userCtx != null ? userCtx.getUserId() : null);
            if (userCtx != null && userCtx.getSecurityContext() != null) {
                SecurityContextHolder.setContext(userCtx.getSecurityContext());
            }
            try {
                // 步骤7：等待异步结果并构建RAGResult
                long t1 = System.currentTimeMillis();
                log.info("[BUILD_PROMPT] >>> 步骤7: 等待异步结果(MCP+记忆)并构建RAGResult");
                log.info("[BUILD_PROMPT] mcpFuture.isDone={}({})",
                        mcpFuture.isDone(),
                        mcpFuture.isCompletedExceptionally() ? "EXCEPTION" : "OK"
                );
                RAGResult ragResult = rag.buildRAGResult(
                        mcpFuture, memorySession,
                        intermediate.rewritten(),
                        intermediate.retrieved(),
                        originalMessage);
                log.info("[BUILD_PROMPT] <<< 步骤7完成: mcpText长度={}, retrieveText长度={}, 耗时={}ms",
                        ragResult.getMcpText() != null ? ragResult.getMcpText().length() : 0,
                        ragResult.getRetrieveText() != null ? ragResult.getRetrieveText().length() : 0,
                        System.currentTimeMillis() - t1);

                // 步骤8：格式化最终Prompt
                log.info("[BUILD_PROMPT] >>> 步骤8: 格式化最终Prompt");
                String finalPrompt = rag.formatFinalPrompt(ragResult, modelInvocation.getSystemMessage());
                log.info("[BUILD_PROMPT] <<< 步骤8完成: finalPrompt长度={}", finalPrompt.length());

                log.info("[BUILD_PROMPT] ==== 构建完成, 总耗时={}ms ====", System.currentTimeMillis() - t1);
                return finalPrompt;
            } finally {
                LoginUserInfoManager.remove();
            }
        })
                .subscribeOn(reactorScheduler);
    }

    /**
     * 执行模型调用并处理响应（步骤9-11）
     */
    @RagTraceNode(name = "模型调用及记忆保存", type = "模型调用")
    public Flux<String> executeModelCall(String finalPrompt, String conversationId,
            String originalMessage, UserContext userCtx) {
        StringBuilder fullAnswer = new StringBuilder();

        return modelInvocation.callModelStream(finalPrompt, conversationId)
                .doOnNext(chunk -> {
                    fullAnswer.append(chunk);
                })
                .doOnComplete(() -> {
                    log.info("[MODEL_CALL] <<< 模型流式响应完成, 总字符数={}", fullAnswer.length());
                    // 步骤11：异步保存记忆
                    modelInvocation.saveMemoryAsync(
                            conversationId,
                            originalMessage,
                            fullAnswer.toString(),
                            userCtx.getUserId());
                })
                .doFinally(signal -> {
                    log.info("[MODEL_CALL] 流结束, signal={}", signal);
                    // 恢复安全上下文
                    if (userCtx.getSecurityContext() != null) {
                        SecurityContextHolder.setContext(userCtx.getSecurityContext());
                    }
                })
                .onErrorResume(e -> {
                    log.error("[MODEL_CALL] 模型路由调用失败: {}", e.getMessage(), e);
                    return Flux.just("抱歉，当前服务繁忙，请稍后再试。");
                });
    }

    /**
     * 快速模式执行模型调用
     */
    @RagTraceNode(name = "快速模型调用", type = "模型调用")
    public Flux<String> executeModelCallFast(String finalPrompt, String conversationId,
            String originalMessage, UserContext userCtx) {
        log.info("[MODEL_CALL_FAST] ==== 开始快速模型调用 ==== thread={}", Thread.currentThread().getName());
        log.info("[MODEL_CALL_FAST] finalPrompt长度={}, conversationId={}", finalPrompt.length(), conversationId);
        StringBuilder fullAnswer = new StringBuilder();

        return modelInvocation.callModelFastStream(finalPrompt, conversationId)
                .doOnNext(chunk -> {
                    fullAnswer.append(chunk);
                })
                .doOnComplete(() -> {
                    log.info("[MODEL_CALL_FAST] <<< 快速模型流式响应完成, 总字符数={}", fullAnswer.length());
                    modelInvocation.saveMemoryAsync(
                            conversationId,
                            originalMessage,
                            fullAnswer.toString(),
                            userCtx.getUserId());
                })
                .doFinally(signal -> {
                    log.info("[MODEL_CALL_FAST] 流结束, signal={}", signal);
                    if (userCtx.getSecurityContext() != null) {
                        SecurityContextHolder.setContext(userCtx.getSecurityContext());
                    }
                })
                .onErrorResume(e -> {
                    log.error("[MODEL_CALL_FAST] 快速模式调用失败: {}", e.getMessage(), e);
                    return Flux.just("抱歉，服务繁忙，请稍后再试。");
                });
    }

    // ==================== 内部类 ====================

    private record RAGIntermediate(RewriteResult rewritten, List<RetrievedChunk> retrieved) {
    }
}