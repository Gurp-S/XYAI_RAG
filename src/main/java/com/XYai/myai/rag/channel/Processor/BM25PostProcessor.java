package com.XYai.myai.rag.channel.Processor;

import com.XYai.myai.rag.channel.POJO.RetrievedChunk;
import com.XYai.myai.rag.channel.POJO.SearchContext;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.stereotype.Component;
import org.wltea.analyzer.lucene.IKAnalyzer;

import java.io.IOException;
import java.util.*;


/**
 * 去重后处理器。
 *
 * <p>定位：召回后处理第一步，优先移除重复 chunk，降低后续过滤与重排成本。</p>
 */
@Slf4j
@Component
public class BM25PostProcessor implements SearchResultPostProcessor {


    private static final Analyzer IK_ANALYZER = new IKAnalyzer(true);

    private static final String NAME = "BM25-processor";

    private static final double K1 = 1.2;
    private static final double B = 0.75;
    private double avgLength;
    private int totalDocs;
    private final Map<String, Integer> wordDocCount = new HashMap<>();


    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return 1;  // 第一个执行，先去重再进行后续处理
    }

    @Override
    public List<RetrievedChunk> process(List<RetrievedChunk> chunks, SearchContext context) {
        // 空输入
        if (chunks == null || chunks.isEmpty()) {
            return chunks;
        }
        wordDocCount.clear();
        avgLength = 0.0;
        totalDocs = 0;
        // 对输入进行分词
        String question = context.getOriginalQuery();
        List<String> questionTokenizes = BM25TokenizeWithIk(question);
        // 对召回的分块进行分词
        List<List<String>> contents = chunks.stream().map(RetrievedChunk::getContent).map(this::BM25TokenizeWithIk).toList();
        this.totalDocs = chunks.size();
        this.avgLength = contents.stream().mapToInt(List::size).average().orElse(0.0);
        if (this.avgLength <= 0) {
            this.avgLength = 1.0;
        }
        buildWordDocCount(contents);
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            if (chunk.getBm25Score() != null) continue;
            List<String> docTokens = contents.get(i);
            double score = calculateScore(questionTokenizes, docTokens);
            chunk.setBm25Score(score);
        }
        return chunks;
    }

    private void buildWordDocCount(List<List<String>> contents) {
        wordDocCount.clear();
        for (List<String> content : contents) {
            Set<String> uniqueWords = new HashSet<>(content);
            for (String word : uniqueWords) {
                wordDocCount.put(word, wordDocCount.getOrDefault(word, 0) + 1);
            }
        }
    }

    public double calculateScore(List<String> questionTokenizes, List<String> chunks) {
        double score = 0.0;
        int docLength = chunks.size();
        for (String word : questionTokenizes) {
            // 跳过不存在的词
            if (!wordDocCount.containsKey(word)) {
                continue;
            }
            double idf = calculateIDF(word);
            int tf = calculateTF(word, chunks);
            double numerator = tf * (K1 + 1);
            double denominator = tf + K1 * (1 - B + B * docLength / avgLength);
            score += idf * numerator / denominator;
        }
        return score;
    }

    private int calculateTF(String word, List<String> doc) {
        return Collections.frequency(doc, word);
    }

    private double calculateIDF(String word) {
        int docCount = wordDocCount.getOrDefault(word, 0);
        return Math.log(1.0 + (totalDocs - docCount + 0.5) / (docCount + 0.5));
    }

    private List<String> BM25TokenizeWithIk(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> tokens = new ArrayList<>();
        try (TokenStream tokenStream = IK_ANALYZER.tokenStream("", text)) {
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
            log.warn("IKAnalyzer 分词失败，回退返回原始文本分割", e);
            // 回退：简单按空格拆分
            String[] parts = text.trim().split("\\s+");
            for (String p : parts) {
                if (!p.isBlank()) tokens.add(p.trim());
            }
        }
        return tokens;
    }
}
