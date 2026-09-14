package com.XYai.myai.rag;

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
import org.springframework.core.task.TaskExecutor;
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
            用户长期记忆(个性化背景,非知识库证据):<<%s>>
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
    private TaskExecutor mcpExecutor;

    // ==================== 步骤1：获取用户上下文 ====================

    public UserContext getUserContext() {
        Long userId = LoginUserInfoManager.getUserId();
        SecurityContext securityCtx = SecurityContextHolder.getContext();
        return new UserContext(userId, securityCtx);
    }

    // ==================== 步骤2：异步加载MCP工具及其结果 ====================

    @RagTraceNode(name = "MCP工具", type = "MCP工具",taskIdArg = "root")
    public CompletableFuture<List<ToolProcessorResult>> loadMCPToolsAsync(String message,LoadSession memorySession,Long conversationId,Long chatMessageId) {
        return CompletableFuture.supplyAsync(
                () -> toolDecisionManager.toolProcessor(message, memorySession,conversationId,chatMessageId),
                mcpExecutor)
                .orTimeout(35, TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    log.error("MCP工具加载失败或超时", ex);
                    return List.of();
                });
    }

    // ==================== 步骤3：异步加载会话记忆 ====================
    @RagTraceNode(name = "加载会话记忆", type = "会话记忆",taskIdArg = "root")
    public LoadSession loadMemoryAsync(Long conversationId) {
        return conversationMemorySummaryService.load(conversationId);
    }

    // ==================== 步骤4：同步执行查询重写 ====================
    @RagTraceNode(name = "查询重写", type = "查询重写",taskIdArg = "root")
    public RewriteResult rewriteQuery(String message,Long conversationId,Long chatMessageId) {
        return queryRewriter.rewrite(message,conversationId,chatMessageId);
    }

    // ==================== 步骤5：同步执行实体识别 ====================
    @RagTraceNode(name = "实体识别", type = "实体识别",taskIdArg = "root")
    public Map<String, Integer> recognizeIntent(String rewrittenUserMessage) {
        return neo4jKnowledgeGraphService.getUserMessageFileChunkId(rewrittenUserMessage);
    }

    // ==================== 步骤6：同步执行多通道文档检索 ====================
    @RagTraceNode(name = "多通道文档检索", type = "文档检索",taskIdArg = "root")
    public List<RetrievedChunk> retrieveDocuments(
            Map<String, Integer> userMessageEntityFileChunkIds,
            RewriteResult rewritten,
            Long conversationId,
            String originalMessage) {
        return multiChannelRetrievalEngine.retrieve(userMessageEntityFileChunkIds, rewritten, conversationId,
                originalMessage);
    }

    // ==================== 步骤7：等待异步结果并构建RAG结果 ====================
    @RagTraceNode(name = "构建RAG结果", type = "构建RAG结果",taskIdArg = "root")
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

        // 引用溯源：每个 chunk 带 [n] 编号与来源标识，供模型按 [n] 引用、前端/审计回溯
        List<String> citations = new java.util.ArrayList<>();
        String retrieveText;
        if (retrieved.isEmpty()) {
            retrieveText = "无";
        } else {
            StringBuilder sb = new StringBuilder();
            int idx = 1;
            for (RetrievedChunk chunk : retrieved) {
                if (chunk == null || chunk.getContent() == null) continue;
                String docId = resolveChunkSource(chunk);
                citations.add(docId);
                sb.append("[").append(idx++).append("] 来源:").append(docId).append("\n")
                  .append(chunk.getContent()).append("\n---\n");
            }
            retrieveText = sb.isEmpty() ? "无" : sb.toString();
        }
        String mcpText = toolResults.isEmpty() ? "无"
                : toolResults.stream()
                  .filter(ToolProcessorResult::isSuccess)
                  .map(t -> t.getToolName() + ":\n" + t.getResult())
                  .collect(Collectors.joining("\n----------------\n"));
        RAGResult result = RAGResult.builder()
                .retrieveText(retrieveText)
                .mcpText(mcpText)
                .summaryText(summaryText)
                .historyText(historyText)
                .originalMessage(originalMessage)
                .citations(citations)
                .insufficientEvidence(false)
                .build();
        return result;
    }

    /** 解析 chunk 来源标识：优先 id，其次 metadata.doc_id */
    private String resolveChunkSource(RetrievedChunk chunk) {
        if (chunk.getId() != null && !chunk.getId().isBlank()) return chunk.getId();
        if (chunk.getMetadata() != null) {
            Object v = chunk.getMetadata().get("doc_id");
            if (v != null) return String.valueOf(v);
        }
        return "unknown";
    }

    // ==================== 步骤8：格式化最终Prompt ====================
    @RagTraceNode(name = "Prompt", type = "Prompt" ,taskIdArg = "root")
    public String formatFinalPrompt(RAGResult ragResult, String systemMessage,String fileContent) {
        String userMessage = String.format(USER_MESSAGE_TEMPLATE,
                ragResult.getRetrieveText(),
                ragResult.getMcpText(),
                ragResult.getSummaryText(),
                ragResult.getHistoryText(),
                ragResult.getLongTermMemoryText() == null || ragResult.getLongTermMemoryText().isBlank()
                        ? "无" : ragResult.getLongTermMemoryText(),
                fileContent,
                ragResult.getOriginalMessage());
        return systemMessage + "\n\n" + userMessage;
    }
}