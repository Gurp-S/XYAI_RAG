package com.XYai.myai.rag.channel.processor;

import cn.hutool.core.util.StrUtil;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.channel.pojo.RetrievalProperties;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 父块展开后处理器（small-to-big）。
 * <p>
 * 子块命中与精排更精准，但内容碎片化；本处理器在精排后将选中子块的
 * 内容替换为其所属章节全文（parent_text，由 Chunker 入库前聚合写入元数据），
 * 让 LLM 拿到完整上下文，同时保持检索侧的精确匹配。
 * </p>
 */
@Slf4j
@Component
public class ParentExpandPostProcessor implements SearchResultPostProcessor {

    private static final String NAME = "parent-expand-processor";

    @Resource
    private RetrievalProperties retrievalProperties;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return 15; // rerank(10) 之后
    }

    @Override
    @RagTraceNode(name = "父块展开", type = "process", taskIdArg = "processRoot")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        if (chunks == null || chunks.isEmpty() || !retrievalProperties.isParentExpandEnabled()) {
            return chunks;
        }
        int expanded = 0;
        for (RetrievedChunk chunk : chunks) {
            if (chunk == null) continue;
            Map<String, Object> meta = chunk.getMetadata();
            if (meta == null) continue;
            Object parentObj = meta.get("parent_text");
            String parentText = parentObj == null ? null : String.valueOf(parentObj);
            if (StrUtil.isBlank(parentText)) continue;
            String childText = chunk.getContent();
            // 父块显著更长时才替换（等长场景无收益，避免无谓 token 膨胀）
            if (parentText.length() > childText.length() * 3 / 2) {
                chunk.setContent(parentText);
                expanded++;
            }
        }
        if (expanded > 0) {
            log.info("父块展开完成: {}/{} 个 chunk 使用章节全文", expanded, chunks.size());
        }
        return chunks;
    }
}
