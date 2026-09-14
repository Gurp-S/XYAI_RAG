package com.XYai.myai.rag.channel.processor;

import com.XYai.myai.commonUtils.IK.IKAnalyzerTokenize;
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
 * 元数据加权 + 最终选择处理器（精排最后一环）。
 *
 * <p>解决"多份同主题文档文件级混淆"问题：当知识库存在两份主题高度相近的文档时，
 * 向量与 rerank 分数区分度有限，最终 Top-K 常被两份文档的候选瓜分，
 * 拉低文件级 Context Precision。本处理器利用入库时已具备的结构化元数据做最后一步加权：</p>
 *
 * <ul>
 *   <li><b>元数据加权</b>：对候选的 fileName（去扩展名）+ section_path/section_title 分词，
 *       计算查询词对元数据的覆盖率，按 weight 加成 final 分数。思想源自
 *       "learning to rank 中的 static features"（Liu, 2009）与 BM25F 的字段加权：
 *       标题/章节等强结构字段的词命中比正文词命中更可信。</li>
 *   <li><b>分数落差自适应截断（elbow）</b>：参考 Adaptive-RAG / 检索结果"谷底检测"
 *       （score-gap based cutoff）思路，在 Top-K 窗口内寻找相邻分数最大落差，
 *       落差超过阈值且位置不小于保底条数时提前截断，剔除长尾噪声候选。</li>
 *   <li><b>最终 Top-K</b>：按加权后的 final 分数排序并截断到 context.topK，
 *       该步骤是精排链路唯一出 backwards-compatible Top-K 的地方。</li>
 * </ul>
 */
@Slf4j
@Component
public class MetadataBoostPostProcessor implements SearchResultPostProcessor {

    private static final String NAME = "metadata-boost-processor";

    @Resource
    private IKAnalyzerTokenize ikAnalyzerTokenize;
    @Resource
    private RetrievalProperties retrievalProperties;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return 15;
    }

    @Override
    @RagTraceNode(name = "metadata-boost", type = "process", taskIdArg = "processRoot")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        String query = resolveQuery(context);
        Set<String> queryTokens = tokenize(query);

        // 1. 元数据加权
        if (retrievalProperties.isMetadataBoostEnabled() && !queryTokens.isEmpty()) {
            double weight = retrievalProperties.getMetadataBoostWeight();
            for (RetrievedChunk chunk : chunks) {
                if (chunk == null) continue;
                double coverage = metadataCoverage(chunk, queryTokens);
                if (coverage > 0) {
                    double boosted = Math.min(1.0, Optional.ofNullable(chunk.getScore()).orElse(0.0) + weight * coverage);
                    chunk.setScore(boosted);
                    Map<String, Object> meta = chunk.getMetadata() != null
                            ? chunk.getMetadata() : new HashMap<>();
                    meta.put("metadata_boost", Math.round(weight * coverage * 1000.0) / 1000.0);
                    meta.put("metadata_coverage", Math.round(coverage * 1000.0) / 1000.0);
                    chunk.setMetadata(meta);
                }
            }
        }

        // 2. 排序
        List<RetrievedChunk> sorted = chunks.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(
                        (RetrievedChunk c) -> Optional.ofNullable(c.getScore()).orElse(0.0)).reversed())
                .collect(Collectors.toCollection(ArrayList::new));

        // 3. 分数落差自适应截断（在 Top-K 窗口内找 elbow）
        Integer topK = Optional.ofNullable(context).map(SearchContext::getTopK).orElse(null);
        int limit = topK != null ? Math.min(topK, sorted.size()) : sorted.size();
        int keep = limit;
        if (retrievalProperties.isScoreElbowEnabled() && sorted.size() > 1) {
            int keepFloor = Math.min(retrievalProperties.getRerankGateKeepTop(), sorted.size());
            double elbowGap = retrievalProperties.getScoreElbowGap();
            int cutIdx = -1;   // 保留 [0, cutIdx]
            double bestGap = 0;
            for (int i = keepFloor - 1; i < limit - 1; i++) {
                double gap = score(sorted.get(i)) - score(sorted.get(i + 1));
                if (gap > bestGap) {
                    bestGap = gap;
                    cutIdx = i;
                }
            }
            if (cutIdx >= 0 && bestGap >= elbowGap) {
                keep = cutIdx + 1;
                log.info("分数落差截断: topK窗口={} 保留={} 最大落差={} (位置{})",
                        limit, keep, String.format("%.3f", bestGap), cutIdx);
            }
        }

        List<RetrievedChunk> result = sorted.subList(0, keep).stream().toList();
        if (log.isDebugEnabled()) {
            log.debug("最终选择 {} 条: {}", result.size(),
                    result.stream().map(c -> String.format("%.3f", score(c))).toList());
        }
        return result;
    }

    /**
     * 查询词对 chunk 元数据（fileName + section_path/section_title）的覆盖率：
     * coverage = 命中的去重查询词数 / 查询词总数 ∈ [0,1]。
     */
    private double metadataCoverage(RetrievedChunk chunk, Set<String> queryTokens) {
        Map<String, Object> meta = chunk.getMetadata();
        if (meta == null) return 0.0;
        StringBuilder sb = new StringBuilder();
        appendMeta(sb, meta.get("fileName"));
        appendMeta(sb, meta.get("section_path"));
        appendMeta(sb, meta.get("section_title"));
        if (sb.length() == 0) return 0.0;
        // 去掉常见扩展名
        String text = sb.toString().replaceAll("\\.(md|txt|pdf|docx?|xlsx?|pptx?|html?)$", "");
        Set<String> metaTokens;
        try {
            metaTokens = new HashSet<>(ikAnalyzerTokenize.tokenize(text));
        } catch (Exception e) {
            log.warn("元数据分词失败: {}", e.getMessage());
            return 0.0;
        }
        if (metaTokens.isEmpty()) return 0.0;
        long hits = queryTokens.stream().filter(metaTokens::contains).count();
        return (double) hits / queryTokens.size();
    }

    private void appendMeta(StringBuilder sb, Object v) {
        if (v == null) return;
        String s = String.valueOf(v).strip();
        if (!s.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(s);
        }
    }

    private Set<String> tokenize(String query) {
        if (query == null || query.isBlank()) return Set.of();
        try {
            List<String> tokens = ikAnalyzerTokenize.tokenize(query);
            return tokens == null ? Set.of() : new HashSet<>(tokens);
        } catch (Exception e) {
            log.warn("查询分词失败: {}", e.getMessage());
            return Set.of();
        }
    }

    private static double score(RetrievedChunk c) {
        return Optional.ofNullable(c.getScore()).orElse(0.0);
    }

    private String resolveQuery(SearchContext context) {
        if (context == null) return "";
        String rewritten = Optional.ofNullable(context.getRewriteQuestion())
                .map(r -> r.getRewrittenQuery())
                .filter(q -> q != null && !q.isBlank())
                .orElse(null);
        return rewritten != null ? rewritten
                : Optional.ofNullable(context.getOriginalQuery()).orElse("");
    }
}
