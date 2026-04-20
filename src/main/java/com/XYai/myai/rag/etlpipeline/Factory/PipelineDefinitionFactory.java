package com.XYai.myai.rag.etlpipeline.Factory;

import com.XYai.myai.rag.etlpipeline.POJO.NodeConfig;
import com.XYai.myai.rag.etlpipeline.POJO.PipelineDefinition;
import com.XYai.myai.rag.etlpipeline.POJO.PipelineProperties;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class PipelineDefinitionFactory {

    @Resource
    private PipelineProperties pipelineProperties;

    public PipelineDefinition create(String fileName, MultipartFile file) {
        return createUploadPipeline(fileName, file);
    }

    public PipelineDefinition createUploadPipeline(String fileName, MultipartFile file) {
        return buildPipeline(fileName, false, resolveChunkSize(file), resolveChunkOverlap(file));
    }

    public PipelineDefinition createSourcePipeline(String sourceLabel, String sourceType) {
        boolean includeFetcher = true;
        int chunkSize = pipelineProperties.getDefaultChunkSize();
        int overlapSize = pipelineProperties.getDefaultOverlapSize();
        return buildPipeline(sourceLabel, includeFetcher, chunkSize, overlapSize);
    }

    public PipelineDefinition createInlinePipeline(String sourceLabel) {
        return buildPipeline(sourceLabel, false, pipelineProperties.getDefaultChunkSize(), pipelineProperties.getDefaultOverlapSize());
    }

    private PipelineDefinition buildPipeline(String pipelineName,
                                             boolean includeFetcher,
                                             int chunkSize,
                                             int overlapSize) {
        ObjectNode chunkSettings = JsonNodeFactory.instance.objectNode();
        chunkSettings.put("chunkSize", chunkSize);
        chunkSettings.put("overlapSize", overlapSize);

        List<NodeConfig> nodes = new ArrayList<>();

        if (includeFetcher) {
            NodeConfig fetcher = NodeConfig.builder()
                    .nodeId("fetcher")
                    .nodeType("fetcher")
                    .nextNodeId("parser")
                    .build();
            nodes.add(fetcher);
        }

        NodeConfig parser = NodeConfig.builder()
                .nodeId("parser")
                .nodeType("parser")
                .nextNodeId(pipelineProperties.getEnricherEnable() ? "enricher" : "chunker")
                .build();
        nodes.add(parser);

        if (pipelineProperties.getEnricherEnable()) {
            NodeConfig enricher = NodeConfig.builder()
                    .nodeId("enricher")
                    .nodeType("enricher")
                    .nextNodeId("chunker")
                    .build();
            nodes.add(enricher);
        }

        NodeConfig chunker = NodeConfig.builder()
                .nodeId("chunker")
                .nodeType("chunker")
                .settings(chunkSettings)
                .nextNodeId("indexer")
                .build();
        nodes.add(chunker);

        NodeConfig indexer = NodeConfig.builder()
                .nodeId("indexer")
                .nodeType("indexer")
                .build();
        nodes.add(indexer);

        return PipelineDefinition.builder()
                .id(UUID.randomUUID().toString())
                .name(pipelineName + "-etl-pipeline")
                .description("upload ->parser -> enricher -> chunker -> indexer")
                .nodes(nodes)
                .build();
    }

    /**
     * 分块大小
     *
     * @param file 上传文件
     * @return 分块大小
     */
    private int resolveChunkSize(MultipartFile file) {
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        String fileName = safeFileName(file).toLowerCase();
        if (type.contains("pdf") || fileName.endsWith(".pdf")) {
            return 1200;
        }
        if (type.contains("word") || fileName.endsWith(".doc") || fileName.endsWith(".docx")) {
            return 1000;
        }
        if (type.contains("plain") || fileName.endsWith(".txt") || fileName.endsWith(".md")) {
            return pipelineProperties.getDefaultChunkSize();
        }
        return pipelineProperties.getDefaultChunkSize();
    }

    /**
     * 分块重叠大小
     *
     * @param file 上传文件
     * @return 重叠大小
     */
    private int resolveChunkOverlap(MultipartFile file) {
        String type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (type.contains("pdf")) {
            return 180;
        }
        if (type.contains("word")) {
            return 150;
        }
        return 120;
    }

    private String safeFileName(MultipartFile file) {
        String original = file.getOriginalFilename();
        return (original == null || original.isBlank()) ? "unknown" : original;
    }
}

