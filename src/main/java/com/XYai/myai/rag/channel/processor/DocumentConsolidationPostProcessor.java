package com.XYai.myai.rag.channel.processor;

import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.channel.pojo.RetrievalProperties;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 文档级整合处理器（cross-document confidence gate）。
 *
 * <p>背景：企业知识库常有多份主题高度相近的文档（如"实施方案" vs "对标研究"），
 * rerank 分数相近时最终 Top-K 会被多份文档的候选瓜分，拉低文件级精度，
 * 而生成侧只需要最相关的那份文档的章节。本处理器把"文档"视为原子知识单元
 * （GraphRAG 社区检索 / 文档路由思想），在精排后做一次跨文档置信度门控：</p>
 *
 * <ul>
 *   <li>按来源文件（doc_id 前缀）对候选分组，组内代表性分数 = max(rerank/融合分)；</li>
 *   <li>最优文档分 D = 各组最大值；</li>
 *   <li>非最优文档的候选仅当其自身分数 ≥ D × docKeepRatio 时保留
 *       （分数相差悬殊的弱源噪声直接剔除），避免同主题多文档互相稀释；</li>
 *   <li>最优文档候选全部保留，保证召回不被误伤。</li>
 * </ul>
 *
 * <p>理论依据：ColBERT/DPR 类流水线中的 score-based document pruning，以及
 * MMR 中"来源冗余惩罚"的对偶形式——这里惩罚的不是同源冗余而是弱源噪声。
 * 当查询确实需要跨文档证据时，把 docKeepRatio 调高（趋近 1）即退化为不整合。</p>
 */
@Slf4j
@Component
public class DocumentConsolidationPostProcessor implements SearchResultPostProcessor {

    private static final String NAME = "document-consolidation-processor";

    @Resource
    private RetrievalProperties retrievalProperties;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return 12;
    }

    @Override
    @RagTraceNode(name = "doc-consolidation", type = "process", taskIdArg = "processRoot")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }
        if (!retrievalProperties.isDocConsolidationEnabled()) {
            return chunks;
        }

        // 1. 按来源文件分组
        Map<String, List<RetrievedChunk>> byDoc = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk == null) continue;
            String fileId = resolveFileId(chunk);
            byDoc.computeIfAbsent(fileId, k -> new ArrayList<>()).add(chunk);
        }
        if (byDoc.size() <= 1) {
            return chunks;
        }

        // 2. 组内代表分（max rerankScore，回退 final score）
        Map<String, Double> docScore = new HashMap<>();
        for (Map.Entry<String, List<RetrievedChunk>> e : byDoc.entrySet()) {
            double best = e.getValue().stream()
                    .mapToDouble(this::representativeScore)
                    .max().orElse(0.0);
            docScore.put(e.getKey(), best);
        }

        // 3. 最优文档分与门控
        String topDoc = docScore.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        double docTop = docScore.getOrDefault(topDoc, 0.0);
        double ratio = retrievalProperties.getDocKeepRatio();

        List<RetrievedChunk> kept = new ArrayList<>(chunks.size());
        for (Map.Entry<String, List<RetrievedChunk>> e : byDoc.entrySet()) {
            String fileId = e.getKey();
            boolean isTopDoc = Objects.equals(fileId, topDoc);
            for (RetrievedChunk chunk : e.getValue()) {
                double s = representativeScore(chunk);
                if (isTopDoc || s >= docTop * ratio) {
                    kept.add(chunk);
                } else {
                    log.debug("文档整合剔除弱源候选: doc={}, score={}, docTop={}, ratio={}",
                            fileId, String.format("%.3f", s),
                            String.format("%.3f", docTop), ratio);
                }
            }
        }

        if (kept.size() != chunks.size()) {
            log.info("文档整合: {} 候选 -> {} (topDoc={}, docTop={}, ratio={})",
                    chunks.size(), kept.size(), topDoc, String.format("%.3f", docTop), ratio);
        }
        return kept;
    }

    /** 解析来源文件：doc_id 形如 fileId:chunkIdx；无 ':' 时整条视为一个文档 */
    private String resolveFileId(RetrievedChunk chunk) {
        if (chunk.getId() != null && !chunk.getId().isBlank()) {
            int i = chunk.getId().indexOf(':');
            return i > 0 ? chunk.getId().substring(0, i) : chunk.getId();
        }
        Map<String, Object> meta = chunk.getMetadata();
        if (meta != null) {
            Object v = meta.get("doc_id");
            if (v != null) {
                String s = String.valueOf(v);
                int i = s.indexOf(':');
                return i > 0 ? s.substring(0, i) : s;
            }
        }
        return "unknown";
    }

    private double representativeScore(RetrievedChunk chunk) {
        Double rs = chunk.getRerankScore();
        if (rs != null && !rs.isNaN()) return rs;
        return Optional.ofNullable(chunk.getScore()).filter(s -> !s.isNaN()).orElse(0.0);
    }
}
