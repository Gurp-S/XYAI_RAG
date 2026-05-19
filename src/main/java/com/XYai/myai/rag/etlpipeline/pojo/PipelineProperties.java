package com.XYai.myai.rag.etlpipeline.pojo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Component
public class PipelineProperties {

    /** 语义增强开关（兼容旧字段，可由 nodes 中的 enricher.enabled 替代） */
    @Builder.Default
    private Boolean enricherEnable = true;

    @Builder.Default
    private Boolean enricherQuestionEnable = true;

    @Builder.Default
    private int defaultMaxParseChars = 10000;

    @Builder.Default
    private Boolean enricherTriplesEnable = true;

    // ==================== 分块配置 ====================

    /** 默认分块大小（当按文件类型无匹配时使用的通用默认值） */
    @Builder.Default
    private int defaultChunkSize = 1024;
    /** 默认最大分块数量 */
    @Builder.Default
    private int maxNumChunks = 1024;
    @Builder.Default
    private int minChunkSizeChars = 12;
    /** 默认分块重叠大小 */
    @Builder.Default
    private int defaultOverlapSize = 64;
    /** PDF 分块大小 */
    @Builder.Default
    private int pdfChunkSize = 1200;

    /** PDF 分块重叠大小 */
    @Builder.Default
    private int pdfOverlapSize = 180;

    /** Word 分块大小 */
    @Builder.Default
    private int wordChunkSize = 1000;

    /** Word 分块重叠大小 */
    @Builder.Default
    private int wordOverlapSize = 150;

    /** 纯文本/Markdown 分块大小 */
    @Builder.Default
    private int textChunkSize = 300;

    /** 纯文本/Markdown 分块重叠大小 */
    @Builder.Default
    private int textOverlapSize = 200;

    /** CSV/Excel 表格分块大小 */
    @Builder.Default
    private int tableChunkSize = 600;

    /** CSV/Excel 表格分块重叠大小 */
    @Builder.Default
    private int tableOverlapSize = 50;

    /** 代码文件分块大小 */
    @Builder.Default
    private int codeChunkSize = 2000;

    /** 代码文件分块重叠大小 */
    @Builder.Default
    private int codeOverlapSize = 100;

    /** HTML/XML 分块大小 */
    @Builder.Default
    private int htmlChunkSize = 800;

    /** HTML/XML 分块重叠大小 */
    @Builder.Default
    private int htmlOverlapSize = 120;

    // ==================== 管道节点定义 ====================

    /**
     * 管道节点定义（有序，按列表顺序串联）
     * 每个节点可独立启用/禁用，支持自定义 nextNodeType
     */
    @Builder.Default
    private List<PipelineNodeDef> nodes = new ArrayList<>(List.of(
            new PipelineNodeDef("fetcher", "获取源文件", true, "parser"),
            new PipelineNodeDef("parser", "解析文档", true, "chunker"),
            new PipelineNodeDef("chunker", "内容分块", true, "enricher"),
            new PipelineNodeDef("enricher", "语义增强", true, "indexer"),
            new PipelineNodeDef("indexer", "向量入库", true, null)));

    /** 所有可能的节点类型 */
    public static final List<String> ALL_NODE_TYPES = List.of("fetcher", "parser", "chunker", "enricher", "indexer");

    /** 节点中文描述映射 */
    public static final Map<String, String> NODE_LABELS = Map.of(
            "fetcher", "获取源文件",
            "parser", "解析文档",
            "chunker", "内容分块",
            "enricher", "语义增强",
            "indexer", "向量入库");

    public PipelineNodeDef getNode(String nodeType) {
        return nodes.stream().filter(n -> n.getNodeType().equals(nodeType)).findFirst().orElse(null);
    }

    public boolean isNodeEnabled(String nodeType) {
        PipelineNodeDef def = getNode(nodeType);
        return def != null && def.isEnabled();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PipelineNodeDef {
        /** 节点类型：fetcher / parser / chunker / enricher / indexer */
        private String nodeType;
        /** 中文显示名 */
        private String label;
        /** 是否启用 */
        @Builder.Default
        private boolean enabled = true;
        /** 下一节点类型（null 表示末端） */
        private String nextNodeType;
    }
}