package com.XYai.myai.rag.channel.processor;

import com.XYai.myai.commonUtils.OllamaRerankerService;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.channel.pojo.RetrievalProperties;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class RerankPostProcessor implements SearchResultPostProcessor {

    private static final String NAME = "rerank-processor";
    private static final double WEIGHT_RETRIEVAL = 0.2;
    private static final double WEIGHT_RERANK = 0.6;
    private static final double WEIGHT_BM25 = 0.2;

    // 用于降级混合排序的权重（可调整）
    private static final double FALLBACK_RETRIEVAL_WEIGHT = 0.7;
    private static final double FALLBACK_BM25_WEIGHT = 0.3;

    @Resource
    private OllamaRerankerService rerankerService;
    @Resource
    private RetrievalProperties retrievalProperties;
    /** 百炼原生重排（rag.rerank.provider=dashscope 时存在，可选依赖） */
    @Resource
    private org.springframework.beans.factory.ObjectProvider<com.XYai.myai.commonUtils.DashscopeRerankerService> dashscopeRerankerProvider;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return 10;
    }

    @Override
    @RagTraceNode(name = "rerank", type = "process" ,taskIdArg = "processRoot")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        if (chunks == null || chunks.isEmpty()) {
            return List.of();
        }

        // 重排查询优先使用重写后的标准查询（BM25/重排语义一致性），无重写时回退原始查询
        String query = resolveQuery(context);

        List<String> contents = chunks.stream()
                .filter(Objects::nonNull)
                .map(this::buildRerankInput)
                .filter(Objects::nonNull)
                .toList();

        if (contents.isEmpty()) {
            log.warn("文本为空，降级为混合排序");
            return fallbackWithHybridScore(chunks, context);
        }

        List<Double> rerankScores = null;
        if (retrievalProperties.getRerankLLM()) {
            rerankScores = rerankWithProviderChain(query, contents);
        }

        if (rerankScores == null || rerankScores.size() != chunks.size()) {
            log.debug("Rerank 未开启或者失败,启用混合排序");
            return fallbackWithHybridScore(chunks, context);
        }

        // 正常 Rerank 融合
        Map<Integer, Double> chunkScores = new HashMap<>();
        for (int i = 0; i < rerankScores.size(); i++) {
            chunkScores.put(i, rerankScores.get(i));
        }

        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            if (chunk == null) continue;

            double retrievalScore = Optional.ofNullable(chunk.getScore()).orElse(0.0);
            double bm25Score = Optional.ofNullable(chunk.getBm25Score()).orElse(0.0);
            double rerankScore = Optional.ofNullable(chunkScores.get(i)).orElse(0.0);

            double finalScore = WEIGHT_RETRIEVAL * retrievalScore +
                    WEIGHT_RERANK * rerankScore +
                    WEIGHT_BM25 * bm25Score;

            chunk.setScore(clamp(finalScore));
            chunk.setRerankScore(rerankScore);
            log.debug("Rerank idx={}: retrieval={}, bm25={}, rerank={}, final={}",
                    i, retrievalScore, bm25Score, rerankScore, chunk.getScore());
        }

        // 重排分数门槛（相对 + 绝对双保险）：
        // 真实分布中相关块分数约 0.25~0.6、跨文件噪声约 0.05~0.2，
        // 相对门槛 = top 分数 × 比例，随查询难度自适应；同时保底保留 N 条防止误杀
        double minRerankScore = retrievalProperties.getMinRerankScore();
        double topScore = chunks.stream()
                .filter(Objects::nonNull)
                .mapToDouble(c -> Optional.ofNullable(c.getRerankScore()).orElse(0.0))
                .max().orElse(0.0);
        double cutoff = Math.max(minRerankScore, topScore * retrievalProperties.getRerankGateRelative());
        List<RetrievedChunk> gated = chunks.stream()
                .filter(c -> c == null || Optional.ofNullable(c.getRerankScore()).orElse(0.0) >= cutoff)
                .toList();
        int keepTop = retrievalProperties.getRerankGateKeepTop();
        if (gated.size() < Math.min(keepTop, chunks.size())) {
            gated = chunks.stream()
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparingDouble(
                            (RetrievedChunk c) -> Optional.ofNullable(c.getRerankScore()).orElse(0.0)).reversed())
                    .limit(keepTop)
                    .collect(Collectors.toList());
            log.debug("Rerank 门槛过滤过严，保底保留 top-{}", keepTop);
        } else if (gated.size() < chunks.size()) {
            log.debug("Rerank 门槛过滤: {} -> {} (cutoff={})", chunks.size(), gated.size(), cutoff);
        }

        // 最终 Top-K 截断已上移至 MetadataBoostPostProcessor（元数据加权后统一排序截断），
        // 此处仅按分数降序返回全部过闸候选
        return gated.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(
                        (RetrievedChunk c) -> Optional.ofNullable(c.getScore()).orElse(0.0)).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 构建重排输入：默认为 chunk 原文；开启 rerank-contextual-input 时前拼
     * 「本文档《X》章节「Y」」确定性元信息（Contextual Reranking）。
     * 注意：必须与 chunks 列表 1:1 对齐（空位返回空串），否则分数错位。
     */
    private String buildRerankInput(RetrievedChunk chunk) {
        if (chunk == null) return "";
        String base = chunk.getContent() == null ? "" : chunk.getContent();
        if (!retrievalProperties.isRerankContextualInput()) return base;
        Map<String, Object> meta = chunk.getMetadata();
        if (meta == null) return base;
        String fileName = Objects.toString(meta.get("fileName"), "");
        String sectionPath = Objects.toString(meta.get("section_path"), "");
        StringBuilder prefix = new StringBuilder();
        if (!fileName.isBlank()) {
            prefix.append("本文档《").append(fileName.replaceAll("\\.(md|txt|pdf|docx?)$", ""));
        }
        if (!sectionPath.isBlank()) {
            prefix.append(prefix.length() > 0 ? "》" : "本文档《未知》");
            prefix.append("章节「").append(sectionPath).append("」");
        } else if (prefix.length() > 0) {
            prefix.append("》");
        }
        return prefix.length() > 0 ? prefix + "\n" + base : base;
    }

    /**
     * 重排提供方选择与降级链：
     * dashscope（百炼 gte-rerank，默认）→ ollama（本地）→ 返回 null（调用方降级混合排序）。
     */
    private List<Double> rerankWithProviderChain(String query, List<String> contents) {
        String provider = Optional.ofNullable(retrievalProperties.getRerankProvider())
                .map(String::toLowerCase)
                .orElse("dashscope");
        // off：完全跳过外部重排
        if ("off".equals(provider)) {
            return null;
        }
        // 1. 主提供方
        List<Double> scores = tryRerank(provider, query, contents);
        if (scores != null && !scores.isEmpty()) {
            return scores;
        }
        // 2. 降级到另一提供方
        String fallback = "dashscope".equals(provider) ? "ollama" : "dashscope";
        scores = tryRerank(fallback, query, contents);
        if (scores != null && !scores.isEmpty()) {
            log.info("Rerank 主提供方[{}]失败，已降级到[{}]", provider, fallback);
            return scores;
        }
        return null;
    }

    private List<Double> tryRerank(String provider, String query, List<String> contents) {
        try {
            List<Double> scores = switch (provider) {
                case "dashscope" -> {
                    var svc = dashscopeRerankerProvider != null ? dashscopeRerankerProvider.getIfAvailable() : null;
                    yield svc == null ? null : svc.rerank(query, contents);
                }
                case "ollama" -> rerankerService.rerank(query, contents);
                default -> null;
            };
            return scores;
        } catch (Exception e) {
            log.warn("Rerank 提供方[{}]调用失败: {}", provider, e.getMessage());
            return null;
        }
    }

    /**
     * 重排/打分查询：优先重写后的标准查询，回退原始查询。
     */
    private String resolveQuery(SearchContext context) {
        if (context == null) {
            return "";
        }
        String rewritten = Optional.ofNullable(context.getRewriteQuestion())
                .map(r -> r.getRewrittenQuery())
                .filter(q -> q != null && !q.isBlank())
                .orElse(null);
        return rewritten != null ? rewritten
                : Optional.ofNullable(context.getOriginalQuery()).orElse("");
    }

    /**
     * 降级混合排序：融合向量分数和 BM25 分数（无需 Rerank）
     */
    private List<RetrievedChunk> fallbackWithHybridScore(List<RetrievedChunk> chunks, SearchContext context) {
        log.debug("使用混合分数降级排序 (向量:{} / BM25:{})", FALLBACK_RETRIEVAL_WEIGHT, FALLBACK_BM25_WEIGHT);
        for (RetrievedChunk chunk : Objects.requireNonNullElse(chunks, Collections.<RetrievedChunk>emptyList())) {
            if (chunk != null) {
                double retrievalScore = Optional.ofNullable(chunk.getScore()).orElse(0.0);
                double bm25Score = Optional.ofNullable(chunk.getBm25Score()).orElse(0.0);
                double hybridScore = FALLBACK_RETRIEVAL_WEIGHT * retrievalScore +
                        FALLBACK_BM25_WEIGHT * bm25Score;
                chunk.setScore(clamp(hybridScore));
            }
        }
        // Top-K 截断上移至 MetadataBoostPostProcessor，此处仅排序
        return chunks.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(
                        (RetrievedChunk c) -> Optional.ofNullable(c.getScore()).orElse(0.0)).reversed())
                .collect(Collectors.toList());
    }

    private static double clamp(Double v) {
        if (v == null || v.isNaN() || v.isInfinite()) return 0.0;
        return Math.clamp(v, 0.0, 1.0);
    }
}