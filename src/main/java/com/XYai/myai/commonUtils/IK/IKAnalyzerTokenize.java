package com.XYai.myai.commonUtils.IK;

import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.springframework.stereotype.Component;
import org.wltea.analyzer.cfg.Configuration;
import org.wltea.analyzer.cfg.DefaultConfig;
import org.wltea.analyzer.dic.Dictionary;
import org.wltea.analyzer.lucene.IKAnalyzer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class IKAnalyzerTokenize {

    private final Dictionary dictionary;
    private final Analyzer analyzer;

    public IKAnalyzerTokenize() {
        // 使用默认配置加载主词典、扩展词和停用词（读取 IK 配置文件）
        Configuration cfg = DefaultConfig.getInstance();
        // 初始化词典（这一步会加载所有配置的词条）
        this.dictionary = Dictionary.initial(cfg);
        // 基于相同的配置和词典创建分词器（确保使用我们自定义的词典）
        this.analyzer = new IKAnalyzer(cfg);
    }

    /**
     * 供外部动态添加自定义词条（如新实体名），添加后立即生效
     */
    public void addWords(List<String> words) {
        if (words != null && !words.isEmpty()) {
            dictionary.addWords(words);
        }
    }

    /**
     * 分词，返回词条列表
     */
    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) return List.of();
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
            log.warn("IK分词异常，返回空列表", e);
        }
        return tokens;
    }
}