package com.XYai.myai.RAG.ETLpipeline.Upload;

import com.huaban.analysis.jieba.JiebaSegmenter;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Advanced TextSplitter: primary bean that supports Chinese tokenization via jieba
 * and a simple sliding-window chunker for other languages. Configurable via
 * SplitterProperties.
 */
@Component
@Primary
public class AdvancedTextSplitter extends TextSplitter {

    private final SplitterProperties props;
    private final JiebaSegmenter jieba = new JiebaSegmenter();

    public AdvancedTextSplitter(SplitterProperties props) {
        this.props = props;
    }

    @Override
    public List<Document> split(List<Document> docs) {
        List<Document> out = new ArrayList<>();
        if (docs == null || docs.isEmpty()) return out;

        for (Document d : docs) {
            if (d == null) continue;
            String text = d.getText();
            if (text == null || text.isBlank()) continue;

            Map<String, Object> baseMeta = new HashMap<>();
            if (d.getMetadata() != null) baseMeta.putAll(d.getMetadata());

            if (props.isChineseTokenize()) {
                // use jieba to smartly split into tokens, then rejoin into chunks
                List<String> tokens = jieba.sentenceProcess(text);
                StringBuilder sb = new StringBuilder();
                int idx = 0, tokenCount = tokens.size();
                int chunkSize = props.getChunkSize();
                int overlap = Math.min(props.getOverlap(), chunkSize / 2);
                int startPos = 0;
                for (int t = 0; t < tokenCount; t++) {
                    String tk = tokens.get(t);
                    if (sb.length() + tk.length() > chunkSize) {
                        String piece = sb.toString().trim();
                        if (!piece.isEmpty()) {
                            Map<String, Object> cm = new HashMap<>(baseMeta);
                            cm.put("chunkIndex", idx);
                            cm.put("chunkStart", startPos);
                            cm.put("chunkEnd", startPos + piece.length());
                            out.add(new Document(piece, cm));
                            idx++;
                        }
                        // prepare next chunk with overlap
                        if (overlap > 0 && sb.length() > overlap) {
                            String keep = sb.substring(sb.length() - overlap);
                            sb = new StringBuilder(keep);
                            startPos += chunkSize - overlap;
                        } else {
                            sb = new StringBuilder();
                            startPos += chunkSize;
                        }
                    }
                    sb.append(tk);
                }
                if (sb.length() > 0) {
                    Map<String, Object> cm = new HashMap<>(baseMeta);
                    cm.put("chunkIndex", out.size());
                    cm.put("chunkStart", startPos);
                    cm.put("chunkEnd", startPos + sb.length());
                    out.add(new Document(sb.toString(), cm));
                }
            } else {
                // fallback to window-based chunking (English etc.)
                int chunkSize = props.getChunkSize();
                int overlap = Math.min(props.getOverlap(), chunkSize / 2);
                int start = 0, idx = 0;
                while (start < text.length()) {
                    int end = Math.min(start + chunkSize, text.length());
                    String piece = text.substring(start, end).trim();
                    if (!piece.isEmpty()) {
                        Map<String, Object> cm = new HashMap<>(baseMeta);
                        cm.put("chunkIndex", idx);
                        cm.put("chunkStart", start);
                        cm.put("chunkEnd", end);
                        out.add(new Document(piece, cm));
                        idx++;
                    }
                    if (end >= text.length()) break;
                    start = Math.max(end - overlap, start + 1);
                }
            }
        }

        return out;
    }

    @Override
    public List<String> splitText(String text) {
        List<Document> docs = split(List.of(new Document(text, new HashMap<>())));
        List<String> out = new ArrayList<>();
        for (Document d : docs) out.add(d.getText());
        return out;
    }
}

