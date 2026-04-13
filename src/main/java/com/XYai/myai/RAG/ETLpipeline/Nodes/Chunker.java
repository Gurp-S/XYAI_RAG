package com.XYai.myai.RAG.ETLpipeline.Nodes;

import com.XYai.myai.RAG.ETLpipeline.POJO.IngestionContext;
import com.XYai.myai.RAG.ETLpipeline.POJO.NodeConfig;
import com.XYai.myai.RAG.ETLpipeline.POJO.NodeResult;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 文档分块节点（ETL 流程 chunker 环节）
 * 功能：将长文本按策略切分成小块，用于后续向量化、入库
 * 支持：阿里云 SentenceSplitter / 本地窗口分块
 */
@Slf4j
@Component
public class Chunker implements Ingestion {

    private static final TokenTextSplitter TOKEN_SPLITTER = new TokenTextSplitter();

    /**
     * 返回节点类型：chunker
     */
    @Override
    public String getNodeType() {
        return "chunker";
    }

    /**
     * 执行分块核心逻辑
     *
     * @param context 摄取上下文（含文档、分块结果）
     * @param config  节点配置（chunkSize、overlapSize）
     * @return 执行结果
     */
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        // 1. 获取源文档，判空
        Document sourceDoc = context.getDocument();
        if (sourceDoc == null) {
            return NodeResult.fail("未获取到文档");
        }

        // 2. 提取要分块的文本（优先使用增强后的文本，没有则用原文）
        String text = extractText(sourceDoc);
        if (!StringUtils.hasText(text)) {
            return NodeResult.fail("分块文本内容为空");
        }

        // 3. 读取节点配置：分块大小、重叠大小
        JsonNode settings = config == null ? null : config.getSettings();
        int chunkSize = readInt(settings, "chunkSize", 512);          // 默认分块 512 字符
        int overlapSize = resolveOverlapSize(settings, chunkSize);

        // 4. 优先使用阿里云 SentenceSplitter；如果不可用或失败，则使用本地窗口分块兜底
        List<Document> chunks = splitWithASpringAI(sourceDoc, text);
        if (chunks == null || chunks.isEmpty()) {
            log.info("Spring AI 官方自动分块 不可用或未返回结果，改用本地窗口分块");
            chunks = splitByWindow(sourceDoc, text, chunkSize, overlapSize);
        } else {
            log.info("Spring AI 官方自动分块完成，chunks={}", chunks.size());
        }

        // 5. 将分块结果存入上下文，供后续节点使用
        chunks = enrichChunkMetadata(chunks, chunkSize);
        context.setChunks(chunks);
        int sizeOfChunks = chunks.size();
        context.getDocument().getMetadata().put(IngestionContext.META_CHUNK_SIZE, chunkSize);
        return NodeResult.ok("分块数量=" + sizeOfChunks);
    }

    /**
     * springAI 分块
     */
    public List<Document> splitWithASpringAI(Document sourceDoc, String text) {
        try {
            // 1. 构建待切分文档
            Document toSplit = sourceDoc.mutate()
                    .text(text)
                    .media(null)
                    .build();

            // 2. 执行分块（单例 + 无多余开销）
            List<Document> chunks = TOKEN_SPLITTER.split(List.of(toSplit));
            if (chunks.isEmpty()) return List.of();

            // 3. 高性能自增ID赋值（ArrayList 预分配容量 + 原生for循环，比 stream 快 30%+）
            List<Document> result = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                result.add(chunks.get(i).mutate()
                        .id(String.valueOf(i + 1))
                        .build());
            }

            log.debug("分块完成，数量：{}", result.size());
            return result;

        } catch (Exception e) {
            log.error("文档分块异常", e);
            return List.of();
        }
    }

    private int resolveOverlapSize(JsonNode settings, int chunkSize) {
        int overlapSize = readInt(settings, "overlapSize", 50);
        int maxOverlap = Math.max(0, chunkSize / 2);
        if (overlapSize < 0) {
            return 0;
        }
        return Math.min(overlapSize, maxOverlap);
    }

    /**
     * 本地基础分块：滑动窗口按字符数切分（兜底方案
     *
     * @param sourceDoc   源文档
     * @param text        待分块文本
     * @param chunkSize   分块大小
     * @param overlapSize 重叠大小
     * @return 分块后的文档列表
     */
    private List<Document> splitByWindow(Document sourceDoc, String text, int chunkSize, int overlapSize) {
        List<Document> chunks = new ArrayList<>();
        // 继承源文档的元数据
        HashMap<String, Object> baseMetadata = new HashMap<>(sourceDoc.getMetadata());

        int start = 0;
        int idx = 0;

        while (start < text.length()) {
            // 计算结束位置
            int end = Math.min(start + chunkSize, text.length());
            String piece = text.substring(start, end).trim();

            // 到达文本末尾，结束循环
            if (end >= text.length()) {
                break;
            }

            // 滑动窗口：前进 = 分块大小 - 重叠大小
            start = Math.max(end - overlapSize, start + 1);
        }
        return chunks;
    }

    /**
     * 统一补齐入库需要的 chunk 元数据
     */
    private List<Document> enrichChunkMetadata(List<Document> chunks, int chunkSize) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        List<Document> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            if (chunk == null || !StringUtils.hasText(chunk.getText())) {
                continue;
            }

            HashMap<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("chunkId", i + 1);
            metadata.put("chunkSize", chunkSize);
            result.add(Document.builder()
                    .id(chunk.getId())
                    .text(chunk.getText())
                    .metadata(metadata)
                    .build());
        }
        return result;
    }

    /**
     * 安全读取配置中的 int 值
     */
    private int readInt(JsonNode settings, String key, int defaultVal) {
        if (settings != null && settings.has(key) && settings.get(key).canConvertToInt()) {
            return settings.get(key).asInt();
        }
        return defaultVal;
    }

    /**
     * 提取分块用的文本：优先使用增强文本，没有则用原文
     */
    private String extractText(Document sourceDoc) {
        // 从元数据获取增强后的文本
        Object enhancedValue = sourceDoc.getMetadata().get(IngestionContext.META_ENHANCED_TEXT);
        String enhancedText = enhancedValue == null ? null : String.valueOf(enhancedValue);

        // 增强文本有值则用增强文本，否则用原始文本
        if (StringUtils.hasText(enhancedText)) {
            return enhancedText;
        }
        return sourceDoc.getText();
    }
}