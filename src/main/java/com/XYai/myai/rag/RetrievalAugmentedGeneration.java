package com.XYai.myai.rag;

import com.XYai.myai.rag.aop.annotation.RagTraceContext;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.channel.MultiChannelRetrievalEngine;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.graph.Neo4jKnowledgeGraphService;
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
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
            用户提交文档:<<%s>>
            用户消息:<<%s>>
            """;

    @Resource
    private QueryRewriter queryRewriter;

    @Resource
    private Neo4jKnowledgeGraphService neo4jKnowledgeGraphService;

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
        return new UserContext(userId, securityCtx);
    }

    // ==================== 步骤2：异步加载MCP工具结果 ====================

    @RagTraceNode(name = "加载MCP工具", type = "MCP工具")
    public CompletableFuture<List<ToolProcessorResult>> loadMCPToolsAsync(String message,LoadSession memorySession,String conversationId,String chatMessageId) {
        return CompletableFuture.supplyAsync(
                () -> {
                    return toolDecisionManager.toolProcessor(message, memorySession,conversationId,chatMessageId);
                },
                mcpExecutor)
                .orTimeout(35, TimeUnit.SECONDS)   // 整体超时35秒
                .exceptionally(ex -> {
                    log.error("MCP工具加载失败或超时", ex);
                    return List.of();  // 降级空列表
                });
    }

    // ==================== 步骤3：异步加载会话记忆 ====================
    @RagTraceNode(name = "加载会话记忆", type = "会话记忆")
    public LoadSession loadMemoryAsync(String conversationId) {
        return conversationMemorySummaryService.load(conversationId);
    }

    // ==================== 步骤4：同步执行查询重写 ====================
    @RagTraceNode(name = "查询重写", type = "查询重写")
    public RewriteResult rewriteQuery(String message,String conversationId,String chatMessageId) {
        return queryRewriter.rewrite(message,conversationId,chatMessageId);
    }

    // ==================== 步骤5：同步执行实体识别 ====================
    @RagTraceNode(name = "实体识别", type = "实体识别")
    public Map<String, Integer> recognizeIntent(String rewrittenUserMessage) {
        return neo4jKnowledgeGraphService.getUserMessageFileChunkId(rewrittenUserMessage);
    }

    // ==================== 步骤6：同步执行多通道文档检索 ====================
    @RagTraceNode(name = "多通道文档检索", type = "文档检索")
    public List<RetrievedChunk> retrieveDocuments(
            Map<String, Integer> userMessageEntityFileChunkIds,
            RewriteResult rewritten,
            String conversationId,
            String originalMessage) {
        return multiChannelRetrievalEngine.retrieve(userMessageEntityFileChunkIds, rewritten, conversationId,
                originalMessage);
    }

    // ==================== 步骤7：等待异步结果并构建RAG结果 ====================
    @RagTraceNode(name = "构建RAG结果", type = "构建RAG结果")
    public RAGResult buildRAGResult(
            CompletableFuture<List<ToolProcessorResult>> mcpFuture,
            LoadSession memorySession,
            RewriteResult rewritten,
            List<RetrievedChunk> retrieved,
            String originalMessage) throws Exception {
        List<ToolProcessorResult> toolResults;
        try {
            toolResults = mcpFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("mcp等待超时或异常, 使用空结果: {}", e.getMessage());
            toolResults = List.of();
        }
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
    public String formatFinalPrompt(RAGResult ragResult, String systemMessage,String fileContent) {
        String userMessage = String.format(USER_MESSAGE_TEMPLATE,
                ragResult.getRetrieveText(),
                ragResult.getMcpText(),
                ragResult.getSummaryText(),
                ragResult.getHistoryText(),
                fileContent,
                ragResult.getOriginalMessage());
        return systemMessage + "\n\n" + userMessage;
    }
}