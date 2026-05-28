package com.XYai.myai.rag.etlpipeline.nodes;

import cn.hutool.core.util.IdUtil;
import com.XYai.myai.rag.aop.annotation.RagTraceContext;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.chat.ModelInvocationService;
import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.rag.etlpipeline.pojo.NodeConfig;
import com.XYai.myai.rag.etlpipeline.pojo.NodeResult;
import com.XYai.myai.rag.etlpipeline.pojo.PipelineProperties;
import com.XYai.myai.rag.graph.Neo4jKnowledgeGraphService;
import com.XYai.myai.user.LoginUserInfoManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class Enricher implements Ingestion {

    private static final long TIMEOUT_SECONDS = 90;
    private static final int MAX_INPUT_LENGTH = 2000;   // 分块文本通常较短

    @Resource
    private ChatModel chatModel;
    @Resource
    private Neo4jKnowledgeGraphService neo4jKnowledgeGraphService;
    @Resource
    private PipelineProperties pipelineProperties;
    @Resource
    private ModelInvocationService modelInvocation;
    @Resource(name = "uploadExecutor")
    private TaskExecutor executor;

    @Override
    public String getNodeType() {
        return "enricher";
    }

    @RagTraceNode(name = "增强", type = "上传管道" ,taskIdArg = "etlNode")
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        if(pipelineProperties.getEnricherEnable()){return NodeResult.ok("增强未开启，跳过增强");}
        List<Document> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.ok("无分块，跳过增强");
        }

        long start = System.currentTimeMillis();

        // 线程安全的收集器：chunkId -> 三元组文本
        Map<String, String> triplesMap = new ConcurrentHashMap<>();

        // 并行处理每个 chunk
        List<CompletableFuture<Document>> futures = chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(() -> enrichChunk(chunk, triplesMap, context), executor))
                .toList();

        List<Document> enrichedChunks = futures.stream()
                .map(future -> {
                    try {
                        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.warn("单个chunk增强超时或失败，使用原chunk", e);
                        return future.getNow(null);
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 若部分失败，用原 chunk 补全
        if (enrichedChunks.size() < chunks.size()) {
            Set<String> enrichedIds = enrichedChunks.stream()
                    .map(Document::getId)
                    .collect(Collectors.toSet());
            chunks.stream()
                    .filter(c -> !enrichedIds.contains(c.getId()))
                    .forEach(enrichedChunks::add);
        }

        context.setChunks(enrichedChunks);

        // 一次性批量写入收集到的三元组
        if (!triplesMap.isEmpty()) {
            try {
                CompletableFuture.runAsync(() -> {
                    neo4jKnowledgeGraphService.batchInsertTriples(triplesMap);
                }, executor);
                log.info("批量写入三元组成功，{} 个chunk", triplesMap.size());
            } catch (Exception e) {
                log.error("批量写入三元组失败，影响 chunk 数: {}", triplesMap.size(), e);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("分块增强完成: 分块数={}, 耗时={}ms", enrichedChunks.size(), elapsed);
        return NodeResult.ok("分块增强完成");
    }

    private Document enrichChunk(Document chunk, Map<String, String> triplesMap, IngestionContext context) {
        String text = chunk.getText();
        if (text == null || text.isBlank()) {
            return chunk;
        }
        String promptInput = text.length() > MAX_INPUT_LENGTH
                ? text.substring(0, MAX_INPUT_LENGTH) : text;

        // 并行生成问题和三元组（LLM调用仍是并行）
        String questions = "";
        String triples = "无";
        if (pipelineProperties.getEnricherQuestionEnable()) {
            CompletableFuture<String> questionsFuture = CompletableFuture
                    .supplyAsync(() -> generateQuestions(promptInput, context), executor);
            try {
                questions = questionsFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("生成问题超时: chunkId={}", chunk.getId());
            }
        }
        if (pipelineProperties.getEnricherTriplesEnable()) {
            CompletableFuture<String> triplesFuture = CompletableFuture
                    .supplyAsync(() -> extractTriples(promptInput, context), executor);
            try {
                triples = triplesFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("三元组提取超时: chunkId={}", chunk.getId());
            }

        }
        // 收集有效的三元组（非空且非“无”）
        if (triples != null && !triples.isBlank() && !"无".equals(triples)) {
            triplesMap.put(chunk.getId(), triples);
        }
        return chunk.mutate()
                .metadata("hypothetical_questions", questions != null ? questions : "")
                .metadata(IngestionContext.META_ENHANCED, "true")
                .build();
    }


    // ======================== 私有方法 ========================

    private String generateQuestions(String fullText, IngestionContext context) {
        String prompt = String.format("""
                你是一个擅长提问的助手。请阅读以下文档，并生成1个用户最可能会对该文档内容提出的问题。
                要求：问题必须覆盖文档的核心信息；直接给出问题，不要编号，不要解释。
                文档：
                %s
                生成的问题：
                """, fullText);
        try {
            long t1 = System.currentTimeMillis();
            String resp = chatModel.call(prompt).trim();
            long cost = System.currentTimeMillis() - t1;
            RagTraceContext.setPhase("文档增强-问题");
            modelInvocation.saveTokenUseAsync(
                    IdUtil.getSnowflakeNextId(),
                    IdUtil.getSnowflakeNextId(),
                    (long) prompt.length(),
                    (long) resp.length(),
                    LoginUserInfoManager.getUserId(),
                    cost,
                    chatModel.getDefaultOptions().getModel(), "upload-enricher");
            return (resp.isEmpty() || resp.startsWith("抱歉")) ? "无" : resp;
        } catch (Exception e) {
            log.error("生成问题失败", e);
            return "";
        }
    }

    private String extractTriples(String fullText, IngestionContext context) {
        String prompt = String.format("""
                你是一个知识图谱构建专家。请从以下文档中提取所有实体及其关系，格式：主体 | 关系 | 客体
                仅输出关系行，无则输出“无”。
                文档全文：%s
                """, fullText);
        try {
            long t1 = System.currentTimeMillis();
            String resp = chatModel.call(prompt).trim();
            long cost = System.currentTimeMillis() - t1;
            RagTraceContext.setPhase("文档增强-三元组");
            modelInvocation.saveTokenUseAsync(
                    IdUtil.getSnowflakeNextId(),
                    IdUtil.getSnowflakeNextId(),
                    (long) prompt.length(),
                    (long) resp.length(),
                    LoginUserInfoManager.getUserId(),
                    cost,
                    chatModel.getDefaultOptions().getModel(), "upload-enricher");
            return (resp.isEmpty() || resp.startsWith("抱歉")) ? "无" : resp;
        } catch (Exception e) {
            log.error("提取三元组失败", e);
            return "无";
        }
    }
}