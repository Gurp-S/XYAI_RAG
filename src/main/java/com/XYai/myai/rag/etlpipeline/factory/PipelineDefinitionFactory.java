package com.XYai.myai.rag.etlpipeline.factory;

import com.XYai.myai.rag.etlpipeline.pojo.NodeConfig;
import com.XYai.myai.rag.etlpipeline.pojo.PipelineDefinition;
import com.XYai.myai.rag.etlpipeline.pojo.PipelineProperties;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 管道定义工厂
 * 根据 PipelineProperties 中配置的 nodes（有序、可开关）动态构建 PipelineDefinition
 */
@Component
public class PipelineDefinitionFactory {

    @Resource
    private PipelineProperties pipelineProperties;

    public PipelineDefinition create(String fileName, MultipartFile file) {
        return createUploadPipeline(fileName, file);
    }

    public PipelineDefinition createUploadPipeline(String fileName, MultipartFile file) {
        return buildPipeline(resolveChunkSize(file), resolveChunkOverlap(file));
    }

    public PipelineDefinition createSourcePipeline(String sourceLabel, String sourceType) {
        // 使用文本类的默认值，因为 sourcePipeline 通常处理结构化较弱的文本
        return buildPipeline(pipelineProperties.getTextChunkSize(), pipelineProperties.getTextOverlapSize());
    }

    /**
     * 根据 PipelineProperties.nodes 构建管道定义
     * 仅包含已启用的节点，按列表顺序串联（nextNodeType 决定连接关系）
     */
    private PipelineDefinition buildPipeline(int chunkSize, int overlapSize) {
        List<NodeConfig> nodes = new ArrayList<>();
        List<PipelineProperties.PipelineNodeDef> enabledDefs = pipelineProperties.getNodes().stream()
                .filter(PipelineProperties.PipelineNodeDef::isEnabled)
                .toList();

        if (enabledDefs.isEmpty()) {
            // 保底：至少需要一个节点
            enabledDefs = List.of(
                    new PipelineProperties.PipelineNodeDef("parser", "解析文档", true, null));
        }

        for (int i = 0; i < enabledDefs.size(); i++) {
            PipelineProperties.PipelineNodeDef def = enabledDefs.get(i);
            String nextId = (i + 1 < enabledDefs.size()) ? enabledDefs.get(i + 1).getNodeType() : null;

            ObjectNode settings = null;
            if ("chunker".equals(def.getNodeType())) {
                settings = JsonNodeFactory.instance.objectNode();
                settings.put("chunkSize", chunkSize);
                settings.put("overlapSize", overlapSize);
                settings.put("minMergeSize", pipelineProperties.getMinChunkSizeChars());
                settings.put("maxNumChunks", pipelineProperties.getMaxNumChunks());
                settings.put("splitLevel", pipelineProperties.getTitleSplitLevel());
            }

            NodeConfig node = NodeConfig.builder()
                    .nodeId(def.getNodeType())
                    .nodeType(def.getNodeType())
                    .nextNodeId(nextId)
                    .settings(settings)
                    .build();
            nodes.add(node);
        }

        return PipelineDefinition.builder()
                .id(UUID.randomUUID().toString())
                .name("etl-pipeline")
                .description("ETL管道: " + String.join(" → ", nodes.stream().map(NodeConfig::getNodeType).toList()))
                .nodes(nodes)
                .build();
    }

    /**
     * 解析文件分块大小
     * 策略：从 PipelineProperties 按文件类型读取，消除硬编码
     */
    private int resolveChunkSize(MultipartFile file) {
        String fileName = safeFileName(file).toLowerCase();
        if (fileName.endsWith(".pdf")) return pipelineProperties.getPdfChunkSize();
        if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) return pipelineProperties.getWordChunkSize();
        if (fileName.endsWith(".txt") || fileName.endsWith(".md") || fileName.endsWith(".markdown"))
            return pipelineProperties.getTextChunkSize();
        if (fileName.endsWith(".csv") || fileName.endsWith(".xls") || fileName.endsWith(".xlsx"))
            return pipelineProperties.getTableChunkSize();
        if (isCodeFile(fileName)) return pipelineProperties.getCodeChunkSize();
        if (fileName.endsWith(".html") || fileName.endsWith(".xml")) return pipelineProperties.getHtmlChunkSize();
        // 默认值：使用文本类的分块大小
        return pipelineProperties.getTextChunkSize();
    }

    /**
     * 解析分块重叠大小
     * 策略：从 PipelineProperties 按文件类型读取，消除硬编码
     */
    private int resolveChunkOverlap(MultipartFile file) {
        String fileName = safeFileName(file).toLowerCase();
        if (fileName.endsWith(".pdf")) return pipelineProperties.getPdfOverlapSize();
        if (fileName.endsWith(".doc") || fileName.endsWith(".docx")) return pipelineProperties.getWordOverlapSize();
        if (fileName.endsWith(".txt") || fileName.endsWith(".md") || fileName.endsWith(".markdown"))
            return pipelineProperties.getTextOverlapSize();
        if (fileName.endsWith(".csv") || fileName.endsWith(".xls") || fileName.endsWith(".xlsx"))
            return pipelineProperties.getTableOverlapSize();
        if (isCodeFile(fileName)) return pipelineProperties.getCodeOverlapSize();
        if (fileName.endsWith(".html") || fileName.endsWith(".xml")) return pipelineProperties.getHtmlOverlapSize();
        // 默认值：使用文本类的重叠大小
        return pipelineProperties.getTextOverlapSize();
    }

    private boolean isCodeFile(String fileName) {
        return fileName.endsWith(".java") || fileName.endsWith(".py") ||
                fileName.endsWith(".js") || fileName.endsWith(".ts") ||
                fileName.endsWith(".go") || fileName.endsWith(".rs") ||
                fileName.endsWith(".cpp") || fileName.endsWith(".c");
    }

    private String safeFileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        return (original == null || original.isBlank()) ? "unknown" : original;
    }
}