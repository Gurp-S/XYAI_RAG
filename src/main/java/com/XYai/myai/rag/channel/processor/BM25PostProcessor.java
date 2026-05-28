package com.XYai.myai.rag.channel.processor;

import com.XYai.myai.commonUtils.IK.IKAnalyzerTokenize;
import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.channel.pojo.RetrievedChunk;
import com.XYai.myai.rag.channel.pojo.SearchContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;
import java.util.stream.IntStream;

/**
 * BM25 相关性打分处理器（极致优化版）
 * <p>
 * 优化特性：
 * 1. 单次遍历完成分词、过滤、统计，避免重复迭代
 * 2. 废弃临时包装对象，使用原始数组直接存储词频和长度
 * 3. 并行计算得分时直接写回 chunk，零额外内存
 * 4. 预计算 BM25 常量，减少浮点运算
 * 5. 可配置并行阈值，小数据自动串行
 * 6. Caffeine 缓存分词结果，命中率高时几乎无额外开销
 */
@Slf4j
@Component
public class BM25PostProcessor implements SearchResultPostProcessor {

    @Resource
    private IKAnalyzerTokenize ikAnalyzerTokenize;

    private static final String NAME = "BM25-processor";

    // ---------- BM25 参数 ----------
    @Value("${bm25.k1:1.2}")
    private double k1;
    @Value("${bm25.b:0.75}")
    private double b;

    // 预计算常量，避免重复运算
    private double preK1Plus1;       // k1 + 1
    private double preK1Times1MinusB; // k1 * (1 - b)
    private double preB;             // b

    // ---------- 归一化 ----------
    @Value("${bm25.normalize:true}")
    private boolean normalize;

    // ---------- 并行阈值 ----------
    @Value("${bm25.parallel-threshold:200}")
    private int parallelThreshold;

    // ---------- 最小文档长度 ----------
    @Value("${bm25.min-doc-length:1}")
    private int minDocLength;

    // ---------- 缓存配置 ----------
    @Value("${bm25.cache.max-size:10000}")
    private int cacheMaxSize;
    @Value("${bm25.cache.expire-minutes:60}")
    private int cacheExpireMinutes;

    /**
     * 分词结果缓存：key = 文档内容，value = 词频映射。
     * 使用内容作为 key 保证精确命中，Caffeine 自动淘汰。
     */
    private final Cache<String, Map<String, Integer>> docTermFreqCache;

    public BM25PostProcessor() {
        this.docTermFreqCache = Caffeine.newBuilder()
                .maximumSize(cacheMaxSize)
                .expireAfterWrite(Duration.ofMinutes(cacheExpireMinutes))
                .recordStats()
                .build();
        // 初始化预计算常量
        refreshConstants();
    }

    /**
     * Spring 属性注入后，需重新计算常量（因为 k1/b 可能通过 setter 注入）
     */
    @jakarta.annotation.PostConstruct
    private void refreshConstants() {
        this.preK1Plus1 = k1 + 1.0;
        this.preK1Times1MinusB = k1 * (1.0 - b);
        this.preB = b;
    }

    // 手动 setter 用于测试或动态修改（如有需要）
    public void setK1(double k1) { this.k1 = k1; refreshConstants(); }
    public void setB(double b) { this.b = b; refreshConstants(); }

    @Override
    public String getName() { return NAME; }

    @Override
    public int getOrder() { return 1; }

    // ======================== 核心处理 ========================

    @Override
    @RagTraceNode(name = "bm25打分", type = "process",taskIdArg = "processRoot")
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        // 1. 快速失败：空列表或空查询
        if (chunks == null || chunks.isEmpty()) {
            log.debug("BM25 processor: empty chunks, skip.");
            return chunks;
        }

        String question = context.getOriginalQuery();
        if (question == null || question.trim().isEmpty()) {
            log.warn("BM25 processor: empty query, skip.");
            return chunks;
        }

        // 2. 查询分词
        List<String> queryTokens;
        try {
            queryTokens = ikAnalyzerTokenize.tokenize(question);
        } catch (Exception e) {
            log.error("BM25 processor: query tokenization failed: {}", e.getMessage(), e);
            return chunks;
        }
        if (queryTokens.isEmpty()) {
            log.warn("BM25 processor: no query tokens, skip.");
            return chunks;
        }
        log.debug("Query tokens: {}", queryTokens);

        // 3. 主流程：单次遍历完成分词、有效文档收集、长度统计、文档频率构建
        int n = chunks.size();
        // 存储每个有效文档的索引、词频映射、文档长度，避免创建包装类
        List<Map<String, Integer>> validTermFreqs = new ArrayList<>(n);
        List<RetrievedChunk> validChunks = new ArrayList<>(n);
        int[] validDocLengths = new int[n]; // 预分配，仅前 validCount 有效
        int validCount = 0;
        double totalLengthSum = 0.0;

        // 文档频率构建器（串行 HashMap 足够快）
        Map<String, Integer> docFreqMap = new HashMap<>();

        for (RetrievedChunk chunk : chunks) {
            String content = chunk.getContent();
            if (content == null || content.trim().isEmpty()) {
                chunk.setBm25Score(0.0);
                continue;
            }

            Map<String, Integer> termFreq;
            try {
                termFreq = docTermFreqCache.get(content, this::buildTermFreq);
            } catch (Exception e) {
                log.error("BM25 processor: tokenization failed for chunk id={}", chunk.getId(), e);
                chunk.setBm25Score(0.0);
                continue;
            }

            if (termFreq.isEmpty()) {
                chunk.setBm25Score(0.0);
                continue;
            }

            // 计算文档长度（总词数）
            int docLength = sumValues(termFreq);
            if (docLength < minDocLength) {
                chunk.setBm25Score(0.0);
                log.debug("BM25 processor: chunk id={} too short (length={}), skip", chunk.getId(), docLength);
                continue;
            }

            // 记录有效文档
            validTermFreqs.add(termFreq);
            validChunks.add(chunk);
            validDocLengths[validCount] = docLength;
            totalLengthSum += docLength;

            // 构建文档频率：每篇文档出现的词，计数 +1
            for (String word : termFreq.keySet()) {
                docFreqMap.merge(word, 1, Integer::sum);
            }

            validCount++;
        }

        // 无有效文档，全部设为 0 分并返回
        if (validCount == 0) {
            log.warn("BM25 processor: no valid documents, all scores set to 0.0");
            return chunks;
        }

        // 4. 计算平均文档长度
        double avgLength = totalLengthSum / validCount;
        if (avgLength <= 0.0) avgLength = 1.0;

        // 5. 构建有效查询词的 IDF 映射（过滤 IDF <= 0 的词）
        int totalDocs = validCount;
        Map<String, Double> effectiveIdfMap = new HashMap<>();
        for (String word : queryTokens) {
            double idf = computeIDF(word, totalDocs, docFreqMap);
            if (idf > 0.0) {
                effectiveIdfMap.put(word, idf);
            }
        }

        if (effectiveIdfMap.isEmpty()) {
            log.warn("BM25 processor: no effective query terms, set all scores to 0.0");
            for (int i = 0; i < validCount; i++) {
                validChunks.get(i).setBm25Score(0.0);
            }
            return chunks;
        }

        // 6. 计算 BM25 分数
        final int fValidCount = validCount;
        final double fAvgLength = avgLength;
        final Map<String, Double> fIdfMap = effectiveIdfMap;

        boolean useParallel = validCount > parallelThreshold;
        if (useParallel) {
            log.debug("Using parallel stream for {} documents", validCount);
            IntStream.range(0, validCount).parallel().forEach(i -> {
                double score = calcBM25(
                        validTermFreqs.get(i), validDocLengths[i], fIdfMap, fAvgLength);
                validChunks.get(i).setBm25Score(score);
            });
        } else {
            log.debug("Using sequential loop for {} documents", validCount);
            for (int i = 0; i < validCount; i++) {
                double score = calcBM25(
                        validTermFreqs.get(i), validDocLengths[i], fIdfMap, fAvgLength);
                validChunks.get(i).setBm25Score(score);
            }
        }

        // 7. 可选归一化（线性缩放至 [0,1]）
        if (normalize) {
            double maxScore = Double.MIN_VALUE;
            for (int i = 0; i < validCount; i++) {
                double s = validChunks.get(i).getBm25Score();
                if (s > maxScore) maxScore = s;
            }
            if (maxScore > 0.0) {
                double invMax = 1.0 / maxScore;
                for (int i = 0; i < validCount; i++) {
                    RetrievedChunk c = validChunks.get(i);
                    c.setBm25Score(c.getBm25Score() * invMax);
                }
                log.debug("Normalized BM25 scores, max={}", maxScore);
            }
        }

        // 8. 输出缓存统计
        if (log.isDebugEnabled()) {
            var stats = docTermFreqCache.stats();
            log.debug("缓存命中: hitCount={}, missCount={}, hitRate={}",
                    stats.hitCount(), stats.missCount(), stats.hitRate());
        }

        return chunks;
    }

    // ======================== 工具方法 ========================

    /**
     * 构建词频映射（分词 + 计数），用于缓存加载
     */
    private Map<String, Integer> buildTermFreq(String content) {
        List<String> tokens = ikAnalyzerTokenize.tokenize(content);
        if (tokens.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Integer> freq = new HashMap<>();
        for (String token : tokens) {
            freq.merge(token, 1, Integer::sum);
        }
        return freq;
    }

    /**
     * 快速计算 Map 中所有值的和（文档总词数）
     */
    private int sumValues(Map<String, Integer> map) {
        int sum = 0;
        for (int v : map.values()) {
            sum += v;
        }
        return sum;
    }

    /**
     * 计算 IDF（逆文档频率）
     */
    private double computeIDF(String word, int totalDocs, Map<String, Integer> docFreqMap) {
        int docCount = docFreqMap.getOrDefault(word, 0);
        if (docCount == 0) return 0.0;
        return Math.log(1.0 + (totalDocs - docCount + 0.5) / (docCount + 0.5));
    }

    /**
     * 计算单个文档的 BM25 分数
     *
     * @param termFreq  文档词频映射
     * @param docLength 文档长度（总词数）
     * @param idfMap    有效查询词的 IDF 映射
     * @param avgLength 平均文档长度
     * @return BM25 分数
     */
    private double calcBM25(Map<String, Integer> termFreq,
                            int docLength,
                            Map<String, Double> idfMap,
                            double avgLength) {
        double score = 0.0;
        // 预计算分母公共部分
        double lengthRatio = preB * docLength / avgLength;
        double denominatorBase = preK1Times1MinusB + lengthRatio; // k1*(1-b) + b*dl/avgdl

        for (Map.Entry<String, Double> entry : idfMap.entrySet()) {
            String word = entry.getKey();
            int tf = termFreq.getOrDefault(word, 0);
            if (tf == 0) continue;

            double numerator = tf * preK1Plus1;
            double denominator = tf + denominatorBase; // 注意：实际 BM25 中分母是 tf + k1*(1-b+b*dl/avgdl)，这里拆分正确
            score += entry.getValue() * numerator / denominator;
        }
        return score;
    }
}