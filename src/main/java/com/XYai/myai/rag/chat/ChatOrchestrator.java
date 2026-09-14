package com.XYai.myai.rag.chat;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.rag.RetrievalAugmentedGeneration;
import com.XYai.myai.rag.aop.annotation.RagTraceContext;
import com.XYai.myai.rag.aop.annotation.RagTraceRoot;
import com.XYai.myai.rag.channel.cache.RetrievalCacheService;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.processor.RetrievalQualityGate;
import com.XYai.myai.rag.chat.pojo.ChatMessage;
import com.XYai.myai.rag.evaluate.pojo.SystemEvaluate;
import com.XYai.myai.rag.evaluate.service.SystemEvaluateService;
import com.XYai.myai.rag.kafka.event.AnalyticsEvent;
import com.XYai.myai.rag.mcp.pojo.ToolProcessorResult;
import com.XYai.myai.rag.memory.LongTermMemoryService;
import com.XYai.myai.rag.memory.pojo.LoadSession;
import com.XYai.myai.rag.ragPojo.RAGResult;
import com.XYai.myai.rag.ragPojo.UserContext;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;
import com.XYai.myai.user.LoginUserInfoManager;
import com.XYai.myai.xyAdmin.pojo.TokenUse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * 聊天编排器（非响应式版）
 * 使用 CompletableFuture + 同步阻塞代替 Flux/Mono，通过 SseEmitter 实现流式输出。
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
    private RetrievalQualityGate retrievalQualityGate;

    @Resource
    private RetrievalCacheService retrievalCacheService;

    @Resource
    private LongTermMemoryService longTermMemoryService;

    @Resource(name = "chatExecutor")
    private TaskExecutor chatExecutor;
    @Resource
    private KafkaTemplate<String,Object> kafkaTemplate;

    /** 尽力而为观测事件（token 统计/评估）专用轻量 producer：acks=1 + 微批 + lz4 */
    @Resource(name = "fastKafkaTemplate")
    private KafkaTemplate<String,Object> fastKafkaTemplate;

    // ==================== 主入口方法 ====================

    @RagTraceRoot(name = "对话主流程", conversationIdArg = "", taskIdArg = "chat")
    public CompletableFuture<Void> chat(String message, Long conversationId, String fileContent, SseEmitter emitter) {
        return doChat(message, conversationId, fileContent, emitter, false);
    }

    @RagTraceRoot(name = "快速对话主流程", conversationIdArg = "", taskIdArg = "chatFast")
    public void chatFast(String message, Long conversationId, String fileContent, SseEmitter emitter) {
        doChat(message, conversationId, fileContent, emitter, true);
    }

    private CompletableFuture<Void> doChat(String message, Long conversationId, String fileContent,
                                           SseEmitter emitter, boolean fast) {
        // 步骤0：参数校验
        if (message == null || message.isBlank()) {
            String modeTag = fast ? "[CHAT_FAST]" : "[CHAT]";
            log.warn("{}参数为空，拒绝请求", modeTag);
            completeEmitterError(emitter, new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有输入信息"));
            return CompletableFuture.completedFuture(null);
        }

        String mode = fast ? "FAST" : "NORMAL";
        log.info("╔══════════════════════════════════════════════╗");
        log.info("║ [{}] 对话流程开始                        ║", mode);
        log.info("╚══════════════════════════════════════════════╝");
        log.info("[{}] message='{}', conversationId={}",
                mode,
                message.length() > 50 ? message.substring(0, 50) + "..." : message,
                conversationId);

        // 步骤1：获取用户上下文
        UserContext userCtx = rag.getUserContext();
        log.info("[{}] 用户上下文获取完成, userId={}", mode, userCtx != null ? userCtx.getUserId() : "null");

        // 生成 chatMessageId（雪花算法 long 值，保证分布式唯一且有序）
        Long chatMessageId = IdUtil.getSnowflakeNextId();
        log.info("[{}] 生成 chatMessageId={}", mode, chatMessageId);

        // 如 conversationId 为空（首次对话），由后端生成雪花 ID
        if (conversationId == null) {
            conversationId = IdUtil.getSnowflakeNextId();
            log.info("[{}] 首次对话，生成 conversationId={}", mode, conversationId);
        }
        final Long finalConvId = conversationId;

        // 发送 start 事件，将 conversationId 和 chatMessageId 返回前端
        try {
            emitter.send(SseEmitter.event()
                    .data("{\"type\":\"start\",\"conversationId\":\"" + finalConvId
                            + "\",\"chatMessageId\":\"" + chatMessageId + "\"}"));
        } catch (IOException e) {
            log.warn("[{}] 发送 start 事件失败", mode, e);
        }

        // 步骤2-3：启动异步任务（MCP工具 + 会话记忆）
        log.info("[{}] >>> 启动异步任务: MCP工具 + 会话记忆", mode);
        LoadSession memorySession = rag.loadMemoryAsync(finalConvId);
        CompletableFuture<List<ToolProcessorResult>> mcpFuture = rag.loadMCPToolsAsync(
                message, memorySession, finalConvId, chatMessageId);
        log.info("[{}] >>> 异步任务已提交", mode);

        // 步骤4-10：在异步线程中执行完整流程
        return CompletableFuture.runAsync(() -> {

            try {
                // Agent 步骤 A：记忆形成——从本轮用户消息抽取长期记忆（LLM 无关，确定性规则）
                Long uid = userCtx != null ? userCtx.getUserId() : null;
                if (uid != null) {
                    longTermMemoryService.remember(uid, message, finalConvId);
                }

                // 步骤4-6：同步执行 RAG 流程
                RAGIntermediate intermediate = executeRAGSync(
                        message, finalConvId, userCtx, chatMessageId, mode);

                // 步骤7：等待 MCP 结果并构建 RAGResult
                List<ToolProcessorResult> mcpResults;
                try {
                    mcpResults = mcpFuture.get();
                } catch (Exception e) {
                    log.warn("[{}] MCP 工具调用超时或异常，降级为空列表", mode, e);
                    mcpResults = Collections.emptyList();
                }
                log.info("[{}] MCP 结果获取完成，数量={}", mode, mcpResults.size());

                RAGResult ragResult = rag.buildRAGResult(
                        CompletableFuture.completedFuture(mcpResults),
                        memorySession,
                        intermediate.rewritten(),
                        intermediate.retrieved(),
                        message);
                ragResult.setInsufficientEvidence(intermediate.insufficientEvidence());

                // Agent 步骤 B：长期记忆召回 → 注入 Prompt 上下文（MemGPT 式 memory retrieval）
                LongTermMemoryService.RecallResult memoryRecall = uid == null
                        ? LongTermMemoryService.RecallResult.empty()
                        : longTermMemoryService.recall(uid, message, 5);
                if (memoryRecall.text() != null && !memoryRecall.text().isBlank()) {
                    ragResult.setLongTermMemoryText(memoryRecall.text());
                }

                // 步骤8：构建最终 Prompt（证据不足时进入决策分支）
                String finalPrompt = rag.formatFinalPrompt(ragResult, modelInvocation.getSystemMessage(), fileContent);
                if (intermediate.insufficientEvidence()) {
                    // Agent 决策：知识库证据不足 → 若长期记忆与问题相关，允许基于记忆作个性化答复；
                    // 否则要求模型明示"知识库无资料"，禁止编造（Agentic 澄清/拒答策略）
                    if (memoryRecall.relevant()) {
                        finalPrompt = finalPrompt + "\n\n(系统提示: 知识库未检索到直接资料，但用户长期记忆与问题相关。"
                                + "可以基于长期记忆作答并标注'这是根据你的过往信息整理的答案'，若记忆不足以回答请向用户澄清，不要编造。)" ;
                        log.info("[{}] 证据不足→走记忆个性化分支 (topScore={})", mode,
                                String.format("%.3f", memoryRecall.topScore()));
                    } else {
                        finalPrompt = finalPrompt + "\n\n(系统提示: 本轮检索证据不足，若无法回答请明确告知用户知识库中暂无相关资料，不要编造。)";
                    }
                }
                log.info("[{}] 最终 Prompt 构建完成，长度={}", mode, finalPrompt.length());

                // 答案缓存 key：归一化问题 + 用户 + 检索指纹（检索结果不变才可复用答案）
                String answerCacheKey = retrievalCacheService.buildAnswerKey(
                        RetrievalCacheService.normalizeQuery(message),
                        userCtx.getUserId(),
                        intermediate.retrieved());

                // 步骤9-10：调用模型流式输出 + 后处理
                executeModelCallStream(finalPrompt, finalConvId, message, userCtx,
                        chatMessageId, fast, emitter, mode, answerCacheKey);

            } catch (Exception e) {
                log.error("[{}] 对话处理异常", mode, e);
                completeEmitterSafe(emitter, "抱歉，当前服务繁忙，请稍后再试。");
            }
        }, chatExecutor);
    }

    // ==================== 健康检查 ====================

    public Map<String, Object> getModelsHealth() {
        return modelInvocation.getHealthStatus();
    }

    // ==================== 私有方法 ====================

    /**
     * 同步执行 RAG 核心流程（查询重写、意图识别、文档检索）
     */
    private RAGIntermediate executeRAGSync(String message, Long conversationId, UserContext userCtx,
                                           Long chatMessageId, String mode) {
        log.info("[RAG_SYNC] ==== 开始同步RAG流程(步骤4-6) ==== mode:{}", mode);
        try {
            if (userCtx != null) {
                LoginUserInfoManager.setUserId(userCtx.getUserId());
                if (userCtx.getSecurityContext() != null) {
                    SecurityContextHolder.setContext(userCtx.getSecurityContext());
                }
            }

            long t1 = System.currentTimeMillis();
            log.info("[RAG_SYNC] >>> 步骤4: 查询重写");
            RewriteResult rewritten = rag.rewriteQuery(message, conversationId, chatMessageId);
            log.info("[RAG_SYNC] <<< 步骤4完成: rewritten={}, 耗时={}ms",
                    rewritten != null ? rewritten.getRewrittenQuery() : "null",
                    System.currentTimeMillis() - t1);

            long t2 = System.currentTimeMillis();
            log.info("[RAG_SYNC] >>> 步骤5: 实体识别");
            Map<String, Integer> userMessageEntityFileChunkIds = rag.recognizeIntent(rewritten.getRewrittenQuery());
            log.info("[RAG_SYNC] <<< 步骤5完成: entity数量={}, 耗时={}ms",
                    userMessageEntityFileChunkIds != null ? userMessageEntityFileChunkIds.size() : 0,
                    System.currentTimeMillis() - t2);

            long t3 = System.currentTimeMillis();
            log.info("[RAG_SYNC] >>> 步骤6: 多通道文档检索");
            List<RetrievedChunk> retrieved = rag.retrieveDocuments(userMessageEntityFileChunkIds, rewritten,
                    conversationId, message);
            log.info("[RAG_SYNC] <<< 步骤6完成: retrieved数量={}, 耗时={}ms",
                    retrieved != null ? retrieved.size() : 0,
                    System.currentTimeMillis() - t3);

            // 步骤6.5：检索质量门控（CRAG 式）——证据不足时改写重检索一次
            RetrievalQualityGate.GateOutcome outcome = retrievalQualityGate.gate(
                    retrieved, userMessageEntityFileChunkIds, rewritten, conversationId, message);
            if (outcome.isRetried()) {
                log.info("[RAG_SYNC] 门控重检索完成: bestScore={}, insufficient={}",
                        outcome.getBestScore(), outcome.isInsufficientEvidence());
            }

            return new RAGIntermediate(rewritten, outcome.getChunks(), outcome.isInsufficientEvidence());
        } finally {
            LoginUserInfoManager.remove();
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * 模型流式调用（同步阻塞，向 SseEmitter 发送流式数据）
     */
    private void executeModelCallStream(String finalPrompt, Long conversationId,
                                        String originalMessage, UserContext userCtx,
                                        Long chatMessageId, boolean fast,
                                        SseEmitter emitter, String mode, String answerCacheKey) {
        log.info("[MODEL_CALL_{}] ==== 开始模型调用 ====", mode);
        log.info("[MODEL_CALL_{}] finalPrompt长度={}, conversationId={}, chatMessageId={}",
                mode, finalPrompt.length(), conversationId, chatMessageId);

        // L4 答案缓存：命中直接回放，跳过 LLM（P99 优化主路径）
        String cachedAnswer = retrievalCacheService.getAnswer(answerCacheKey);
        if (cachedAnswer != null) {
            log.info("[MODEL_CALL_{}] 答案缓存命中，回放缓存回答，长度={}", mode, cachedAnswer.length());
            try {
                for (int i = 0; i < cachedAnswer.length(); i += 100) {
                    String piece = cachedAnswer.substring(i, Math.min(i + 100, cachedAnswer.length()));
                    emitter.send(SseEmitter.event().data(piece));
                }
            } catch (IOException e) {
                log.warn("[MODEL_CALL_{}] 缓存回放 SSE 发送中断", mode, e);
            }
            completeEmitterDone(emitter);
            return;
        }

        StringBuilder fullAnswer = new StringBuilder();
        long startTime = System.nanoTime();

        // onChunk: 收集响应并发送到 SseEmitter
        Consumer<String> onChunk = chunk -> {
            fullAnswer.append(chunk);
            try {
                emitter.send(SseEmitter.event().data(chunk));
            } catch (IOException e) {
                throw new RuntimeException("SSE 发送中断", e);
            }
        };

        // onComplete: 流式结束，处理后续任务
        String[] modelNameRef = new String[1];
        Runnable onComplete = () -> {
            log.info("[MODEL_CALL_{}] <<< 模型流式响应完成，总字符数={}, 耗时={}ms",
                    mode, fullAnswer.length(), (System.nanoTime() - startTime) / 1_000_000);

            String answer = fullAnswer.toString();

            // 写入答案缓存（检索结果不变时后续同样问题直接回放）
            retrievalCacheService.putAnswer(answerCacheKey, answer);

            // 异步保存对话记忆
            modelInvocation.saveMemory(
                    conversationId, chatMessageId, originalMessage, answer, userCtx.getUserId());

            // 异步系统评估
            SystemEvaluate systemEvaluate = new SystemEvaluate(
                    conversationId,
                    ChatMessage.builder()
                            .chatMessageId(chatMessageId)
                            .userMessage(originalMessage)
                            .assistantMessage(answer)
                            .userId(userCtx.getUserId())
                            .build(),
                    List.of(),
                    (System.nanoTime() - startTime) / 1_000_000,
                    originalMessage);
            fastKafkaTemplate.send("analytics-event", null, new AnalyticsEvent(
                    UUID.randomUUID().toString(),"evaluate",
                    null,null,systemEvaluate
            ));
            // 完成 SSE
            completeEmitterDone(emitter);
        };

        // onError: 降级提示
        Consumer<Throwable> onError = error -> {
            log.error("[MODEL_CALL_{}] 模型调用失败: {}", mode, error.getMessage());
            if (fullAnswer.isEmpty()) {
                completeEmitterSafe(emitter, "抱歉，当前服务繁忙，请稍后再试。");
            }
        };

        // 执行流式调用（同步阻塞，返回模型名称）
        if (fast) {
            modelNameRef[0] = modelInvocation.callModelFastStream(
                    finalPrompt, conversationId, onChunk, onError, onComplete);
        } else {
            modelNameRef[0] = modelInvocation.callModelStream(
                    finalPrompt, conversationId, onChunk, onError, onComplete);
        }

        // 流式结束后保存 Token 消耗
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        RagTraceContext.setPhase("模型路由");
        TokenUse tokenUse = new TokenUse(
                conversationId, chatMessageId,
                finalPrompt.length(), fullAnswer.toString().length(),
                userCtx.getUserId(), durationMs, modelNameRef[0], "chat");
        fastKafkaTemplate.send("analytics-event", null, new AnalyticsEvent(
                UUID.randomUUID().toString(),"save_token",
                null,tokenUse,null
        ));
        log.info("[MODEL_CALL_{}] 模型调用结束, 模型: {}", mode, modelNameRef[0]);
    }

    // ==================== SseEmitter 辅助方法 ====================

    private void completeEmitterError(SseEmitter emitter, Throwable error) {
        try {
            emitter.send(SseEmitter.event().data(error.getMessage()));
        } catch (IOException ignored) {
        }
        emitter.completeWithError(error);
    }

    private void completeEmitterSafe(SseEmitter emitter, String fallbackMessage) {
        try {
            emitter.send(SseEmitter.event().data(fallbackMessage));
            emitter.complete();
        } catch (IOException ignored) {
        }
    }

    private void completeEmitterDone(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
        }
    }

    // ==================== 内部类 ====================

    private record RAGIntermediate(RewriteResult rewritten, List<RetrievedChunk> retrieved, boolean insufficientEvidence) {
    }
}
