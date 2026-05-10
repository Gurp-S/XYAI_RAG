package com.XYai.myai.rag.etlpipeline.nodes;

import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.rag.etlpipeline.pojo.NodeConfig;
import com.XYai.myai.rag.etlpipeline.pojo.NodeResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Component
public class Enricher implements Ingestion {

    private static final long TIMEOUT_SECONDS = 90;
    private static final int MAX_INPUT_LENGTH = 2000;   // 分块文本通常较短
    @Resource
    private ChatModel chatModel;
    @Resource(name = "uploadExecutor")
    private ThreadPoolTaskExecutor executor;

    @Override
    public String getNodeType() {
        return "enricher";
    }

    @RagTraceNode(name = "增强", type = "上传管道")
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        List<Document> chunks = context.getChunks();
        if (chunks == null || chunks.isEmpty()) {
            return NodeResult.ok("无分块，跳过增强");
        }

        long start = System.currentTimeMillis();

        // 并行处理每个 chunk
        List<CompletableFuture<Document>> futures = chunks.stream()
                .map(chunk -> CompletableFuture.supplyAsync(() -> enrichChunk(chunk), executor))
                .toList();

        // 等待所有任务完成，汇总增强后的 chunk
        List<Document> enrichedChunks = futures.stream()
                .map(future -> {
                    try {
                        return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.warn("单个chunk增强超时或失败，使用原chunk", e);
                        return future.getNow(null);  // 可能为 null，后面过滤
                    }
                })
                .filter(Objects::nonNull)
                .toList();

        // 如果部分 chunk 失败，用原 chunk 补全（确保数量一致）
        if (enrichedChunks.size() < chunks.size()) {
            Set<String> enrichedIds = enrichedChunks.stream()
                    .map(Document::getId)
                    .collect(Collectors.toSet());
            chunks.stream()
                    .filter(c -> !enrichedIds.contains(c.getId()))
                    .forEach(enrichedChunks::add);
        }

        context.setChunks(enrichedChunks);

        long elapsed = System.currentTimeMillis() - start;
        log.info("分块增强完成: 分块数={}, 耗时={}ms", enrichedChunks.size(), elapsed);
        return NodeResult.ok("分块增强完成");
    }

    private Document enrichChunk(Document chunk) {
        String text = chunk.getText();
        if (text == null || text.isBlank()) {
            return chunk;
        }
        String promptInput = text.length() > MAX_INPUT_LENGTH
                ? text.substring(0, MAX_INPUT_LENGTH) : text;

        // 并行生成问题和三元组
        CompletableFuture<String> questionsFuture = CompletableFuture
                .supplyAsync(() -> generateQuestions(promptInput), executor);
        CompletableFuture<String> triplesFuture = CompletableFuture
                .supplyAsync(() -> extractTriples(promptInput), executor);

        String questions = "";
        String triples = "无";
        try {
            questions = questionsFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("生成问题超时: chunkId={}", chunk.getId());
        }
        try {
            triples = triplesFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("三元组提取超时: chunkId={}", chunk.getId());
        }

        // 将生成的内容存入该 chunk 的 metadata
        return chunk.mutate()
                .metadata("hypothetical_questions", questions != null ? questions : "")
                .metadata("knowledge_triples", triples != null ? triples : "无")
                .metadata(IngestionContext.META_ENHANCED, "true")
                .build();
    }
    // ======================== 私有方法 ========================

    private String generateQuestions(String fullText) {
        String prompt = String.format("""
            你是一个擅长提问的助手。请阅读以下文档，并生成1个用户最可能会对该文档内容提出的问题。
            要求：问题必须覆盖文档的核心信息；直接给出问题，不要编号，不要解释。
            文档：
            %s
            生成的问题：
            """, fullText);
        try {
            return chatModel.call(prompt).trim();
        } catch (Exception e) {
            log.error("生成问题失败", e);
            return "";
        }
    }

    private String extractTriples(String fullText) {
        String prompt = String.format("""
                你是一个知识图谱构建专家。请从以下文档中提取所有实体及其关系，格式：主体 | 关系 | 客体
                仅输出关系行，无则输出“无”。
                文档全文：%s
                """, fullText);
        try {
            String resp = chatModel.call(prompt).trim();
            return (resp.isEmpty() || resp.startsWith("抱歉")) ? "无" : resp;
        } catch (Exception e) {
            log.error("提取三元组失败", e);
            return "无";
        }
    }
}