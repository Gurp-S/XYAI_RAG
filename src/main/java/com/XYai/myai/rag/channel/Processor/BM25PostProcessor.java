package com.XYai.myai.rag.channel.Processor;

import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.channel.POJO.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.stereotype.Component;
import org.wltea.analyzer.lucene.IKAnalyzer;

import java.io.IOException;
import java.util.*;

/**
 * BM25 相关性打分处理器（线程安全版）
 * 修复并行调用导致的变量覆盖、分词异常问题
 */
@Slf4j
@Component
public class BM25PostProcessor implements SearchResultPostProcessor {

    private static final String NAME = "BM25-processor";
    // BM25 固定参数
    private static final double K1 = 1.2;
    private static final double B = 0.75;

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return 1;
    }

    /**
     * 核心处理方法
     */
    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        if (chunks == null || chunks.isEmpty()) {
            return chunks;
        }

        // 1. 创建独立的 IKAnalyzer
        IKAnalyzer ikAnalyzer = new IKAnalyzer(true);
        // 2. 本次请求独立的状态变量
        String question = context.getOriginalQuery();
        List<String> questionTokens = tokenize(ikAnalyzer, question);
        List<List<String>> docTokensList = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            docTokensList.add(tokenize(ikAnalyzer, chunk.getContent()));
        }
        // 3. 本次请求独立计算 BM25 统计值
        int totalDocs = chunks.size();
        double avgLength = docTokensList.stream().mapToInt(List::size).average().orElse(1.0);
        Map<String, Integer> wordDocCount = buildWordDocCount(docTokensList);
        // 4. 逐文档计算 BM25 分数
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            if (chunk.getBm25Score() != null) {
                continue;
            }
            List<String> docTokens = docTokensList.get(i);
            double score = calculateScore(questionTokens, docTokens, totalDocs, avgLength, wordDocCount);
            chunk.setBm25Score(score);
        }
        // 关闭分词器
        ikAnalyzer.close();
        return chunks;
    }

    /**
     * 构建词-文档频数字典
     */
    public Map<String, Integer> buildWordDocCount(List<List<String>> docTokensList) {
        Map<String, Integer> wordDocCount = new HashMap<>();
        for (List<String> docTokens : docTokensList) {
            Set<String> uniqueWords = new HashSet<>(docTokens);
            for (String word : uniqueWords) {
                wordDocCount.put(word, wordDocCount.getOrDefault(word, 0) + 1);
            }
        }
        return wordDocCount;
    }

    /**
     * 计算单文档 BM25 分数
     */
    public double calculateScore(List<String> questionTokens, List<String> docTokens,
                                 int totalDocs, double avgLength, Map<String, Integer> wordDocCount) {
        double score = 0.0;
        int docLength = docTokens.size();
        for (String word : questionTokens) {
            if (!wordDocCount.containsKey(word)) {
                continue;
            }
            double idf = calculateIDF(word, totalDocs, wordDocCount);
            int tf = calculateTF(word, docTokens);
            double numerator = tf * (K1 + 1);
            double denominator = tf + K1 * (1 - B + B * docLength / avgLength);
            score += idf * numerator / denominator;
        }
        return score;
    }

    /**
     * 词频 TF 计算
     */
    public int calculateTF(String word, List<String> docTokens) {
        return Collections.frequency(docTokens, word);
    }

    /**
     * 逆文档频率 IDF 计算
     */
    public double calculateIDF(String word, int totalDocs, Map<String, Integer> wordDocCount) {
        int docCount = wordDocCount.get(word);
        return Math.log(1.0 + (totalDocs - docCount + 0.5) / (docCount + 0.5));
    }

    /**
     * IK 分词
     */
    public List<String> tokenize(IKAnalyzer analyzer, String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        try (TokenStream tokenStream = analyzer.tokenStream("", text)) {
            CharTermAttribute termAttr = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                String term = termAttr.toString().trim();
                if (!term.isEmpty()) {
                    tokens.add(term);
                }
            }
            tokenStream.end();
        } catch (IOException e) {
            log.warn("IK分词失败，文本：{}", text, e);
        }
        return tokens;
    }
}