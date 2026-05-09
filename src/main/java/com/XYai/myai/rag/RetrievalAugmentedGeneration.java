package com.XYai.myai.rag;

import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.channel.MultiChannelRetrievalEngine;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.intent.IntentResult;
import com.XYai.myai.rag.intent.pojo.SubQuestionIntent;
import com.XYai.myai.rag.mcp.ToolDecisionManager;
import com.XYai.myai.rag.mcp.pojo.ToolProcessorResult;
import com.XYai.myai.rag.memory.ConversationMemorySummaryService;
import com.XYai.myai.rag.memory.pojo.LoadSession;
import com.XYai.myai.rag.ragPojo.RAGResult;
import com.XYai.myai.rag.ragPojo.UserContext;
import com.XYai.myai.rag.rewrite.QueryRewriter;
import com.XYai.myai.rag.rewrite.pojo.RewriteResult;
import com.XYai.myai.user.LoginUserInfoManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * RAG（检索增强生成）处理服务
 * 步骤1-8：用户信息、MCP、Memory、重写、意图、检索、构建Prompt
 */
@Slf4j
@Component
public class RetrievalAugmentedGeneration {

    private static final String USER_MESSAGE_TEMPLATE = """
            参考文档:<<%s>>
            工具调用结果:<<%s>>
            更早的历史对话摘要:<<%s>>
            历史对话:<<%s>>
            用户消息:<<%s>>
            """;

    @Resource
    private QueryRewriter queryRewriter;

    @Resource
    private IntentResult intentResult;

    @Resource
    private MultiChannelRetrievalEngine multiChannelRetrievalEngine;

    @Resource
    private ToolDecisionManager toolDecisionManager;

    @Resource
    private ConversationMemorySummaryService conversationMemorySummaryService;

    @Resource(name = "mcpExecutor")
    private ThreadPoolTaskExecutor mcpExecutor;

    @Resource(name = "memeryExecutor")
    private ThreadPoolTaskExecutor memoryExecutor;

    // ==================== 步骤1：获取用户上下文 ====================

    public UserContext getUserContext() {
        Long userId = LoginUserInfoManager.getUserId();
        SecurityContext securityCtx = SecurityContextHolder.getContext();
        log.info("[RAG] 步骤1: 获取用户上下文, userId={}, thread={}", userId, Thread.currentThread().getName());
        return new UserContext(userId, securityCtx);
    }

    // ==================== 步骤2：异步加载MCP工具结果 ====================

    @RagTraceNode(name = "加载MCP工具", type = "MCP工具")
    public CompletableFuture<List<ToolProcessorResult>> loadMCPToolsAsync(String message,LoadSession memorySession) {
        log.info("[RAG] 步骤2: 提交MCP工具异步任务, thread={}", Thread.currentThread().getName());
        return CompletableFuture.supplyAsync(
                () -> {
                    log.info("[RAG] 步骤2.mcp: MCP任务开始执行, thread={}", Thread.currentThread().getName());
                    long t1 = System.currentTimeMillis();
                    List<ToolProcessorResult> results = toolDecisionManager.toolProcessor(message, memorySession);
                    log.info("[RAG] 步骤2.mcp: MCP任务完成, 结果数={}, 耗时={}ms",
                            results.size(), System.currentTimeMillis() - t1);
                    return results;
                },
                mcpExecutor);
    }

    // ==================== 步骤3：异步加载会话记忆 ====================
    @RagTraceNode(name = "加载会话记忆", type = "会话记忆")
    public LoadSession loadMemoryAsync(String conversationId) {
        log.info("[RAG] 步骤3: 提交会话记忆异步任务, thread={}", Thread.currentThread().getName());
        log.info("[RAG] 步骤3.mem: 会话记忆任务开始执行, thread={}", Thread.currentThread().getName());
        long t1 = System.currentTimeMillis();
        LoadSession session = conversationMemorySummaryService.load(conversationId);
        log.info("[RAG] 步骤3.mem: 会话记忆任务完成, hasHistory={}, 耗时={}ms",
                session != null && session.getHistoryAsText() != null,
                System.currentTimeMillis() - t1);
        return session;
    }

    // ==================== 步骤4：同步执行查询重写 ====================
    @RagTraceNode(name = "查询重写", type = "查询重写")
    public RewriteResult rewriteQuery(String message) {
        log.info("[RAG] 步骤4: 查询重写, thread={}", Thread.currentThread().getName());
        long t1 = System.currentTimeMillis();
        RewriteResult result = queryRewriter.rewrite(message, null);
        log.info("[RAG] 步骤4完成: rewritten='{}', 耗时={}ms",
                result != null ? result.getRewrittenQuery() : "null",
                System.currentTimeMillis() - t1);
        return result;
    }

    // ==================== 步骤5：同步执行意图识别 ====================
    @RagTraceNode(name = "意图识别", type = "意图识别")
    public List<SubQuestionIntent> recognizeIntent(RewriteResult rewritten) {
        log.info("[RAG] 步骤5: 意图识别, thread={}", Thread.currentThread().getName());
        long t1 = System.currentTimeMillis();
        List<SubQuestionIntent> intents = intentResult.recognize(rewritten, null);
        log.info("[RAG] 步骤5完成: intents数量={}, 耗时={}ms",
                intents != null ? intents.size() : 0,
                System.currentTimeMillis() - t1);
        return intents;
    }

    // ==================== 步骤6：同步执行多通道文档检索 ====================
    @RagTraceNode(name = "多通道文档检索", type = "文档检索")
    public List<RetrievedChunk> retrieveDocuments(
            List<SubQuestionIntent> intents,
            RewriteResult rewritten,
            String conversationId,
            String originalMessage) {
        log.info("[RAG] 步骤6: 多通道文档检索, intents数={}, thread={}",
                intents != null ? intents.size() : 0, Thread.currentThread().getName());
        long t1 = System.currentTimeMillis();
        List<RetrievedChunk> results = multiChannelRetrievalEngine.retrieve(intents, rewritten, conversationId,
                originalMessage);
        log.info("[RAG] 步骤6完成: retrieved数量={}, 耗时={}ms",
                results != null ? results.size() : 0,
                System.currentTimeMillis() - t1);
        return results;
    }

    // ==================== 步骤7：等待异步结果并构建RAG结果 ====================
    @RagTraceNode(name = "构建RAG结果", type = "构建RAG结果")
    public RAGResult buildRAGResult(
            CompletableFuture<List<ToolProcessorResult>> mcpFuture,
            LoadSession memorySession,
            RewriteResult rewritten,
            List<RetrievedChunk> retrieved,
            String originalMessage) throws Exception {

        log.info("[RAG] 步骤7: 等待异步结果并构建RAGResult, thread={}", Thread.currentThread().getName());

        long t1 = System.currentTimeMillis();
        log.info("[RAG] 步骤7.mcp: 等待MCP结果... mcpFuture.isDone={}, isCompletedExceptionally={}",
                mcpFuture.isDone(), mcpFuture.isCompletedExceptionally());
        List<ToolProcessorResult> toolResults = mcpFuture.getNow(List.of());
        log.info("[RAG] 步骤7.mcp: MCP结果获取成功, 共{}个, 耗时={}ms",
                toolResults.size(), System.currentTimeMillis() - t1);
        for (ToolProcessorResult r : toolResults) {
            log.info("[RAG] 步骤7.mcp:   工具'{}' success={} result预览={}",
                    r.getToolName(), r.isSuccess(),
                    r.getResult() != null && r.getResult().length() > 100
                            ? r.getResult().substring(0, 100) + "..."
                            : r.getResult());
        }

        long t2 = System.currentTimeMillis();
        log.info("[RAG] 步骤7.mem: 记忆结果获取成功, 耗时={}ms", System.currentTimeMillis() - t2);

        String historyText = (memorySession == null) ? "无" : memorySession.getHistoryAsText();
        String summaryText = (memorySession == null) ? "无" : memorySession.getSummary();

        String retrieveText = retrieved.isEmpty() ? "无"
                : retrieved.stream()
                  .map(RetrievedChunk::getContent)
                  .filter(Objects::nonNull)
                  .collect(Collectors.joining("\n---\n"));

        String mcpText = toolResults.isEmpty() ? "无"
                : toolResults.stream()
                  .filter(ToolProcessorResult::isSuccess)
                  .map(t -> t.getToolName() + ":\n" + t.getResult())
                  .collect(Collectors.joining("\n----------------\n"));

        return new RAGResult(retrieveText, mcpText, summaryText, historyText, originalMessage);
    }

    // ==================== 步骤8：格式化最终Prompt ====================
    @RagTraceNode(name = "Prompt", type = "Prompt")
    public String formatFinalPrompt(RAGResult ragResult, String systemMessage) {
        log.info("[RAG] 步骤8: 格式化最终Prompt, thread={}", Thread.currentThread().getName());
        log.info("[RAG] 步骤8: systemMessage长度={}, mcpText长度={}, retrieveText长度={}",
                systemMessage != null ? systemMessage.length() : 0,
                ragResult.getMcpText() != null ? ragResult.getMcpText().length() : 0,
                ragResult.getRetrieveText() != null ? ragResult.getRetrieveText().length() : 0);

        String userMessage = String.format(USER_MESSAGE_TEMPLATE,
                ragResult.getRetrieveText(),
                ragResult.getMcpText(),
                ragResult.getSummaryText(),
                ragResult.getHistoryText(),
                ragResult.getOriginalMessage());

        String finalPrompt = systemMessage + "\n\n" + userMessage;
        log.info("[RAG] 步骤8完成: finalPrompt总长度={}", finalPrompt.length());
        return finalPrompt;
    }
}