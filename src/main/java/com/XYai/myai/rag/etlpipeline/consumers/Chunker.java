package com.XYai.myai.rag.etlpipeline.consumers;

import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import jakarta.annotation.Resource;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.*;
import org.commonmark.node.*;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 混合分块组件：分层 + 语义。
 * - 优先按标题或自然段切分（分层骨架）
 * - 对超过大小阈值的段落，使用语义相似度在句子间寻找最佳分割点
 */
@Slf4j
@Component
public class Chunker {

    @Resource
    private EmbeddingModel embeddingModel;

    /** 分块参数可配置（rag.retrieval.max-chunk-size 等）；4096 旧默认导致单块语义稀释 */
    @Resource
    private com.XYai.myai.rag.channel.pojo.RetrievalProperties retrievalProperties;

    @Setter
    private TokenCounter tokenCounter = new DefaultTokenCounter();

    private static final Parser MARKDOWN_PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();

    private static final Pattern SENT_CN = Pattern.compile("(?<=[。！？])");
    private static final Pattern SENT_EN = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern SENT_MIXED = Pattern.compile("(?<=[。！？.!?])\\s*");

    // 可调参数
    private static final int DEFAULT_MAX_CHUNK_SIZE = 4096;
    private static final int DEFAULT_MIN_MERGE_SIZE = 20;          // token 数
    private static final int DEFAULT_CHUNK_OVERLAP = 10;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.6;
    private static final int MIN_PARAGRAPH_TOKENS = 20;           // 段落内极小合并阈值
    private static final int FULL_TEXT_SHORT_THRESHOLD = 50;      // 全文短文本保护 token 阈值

    // 自定义标题匹配（中文数字、括号编号、带章节号等）
    private static final Pattern CUSTOM_HEADING = Pattern.compile(
            "^(?:[（(]?[一二三四五六七八九十]+[）)]?[、.]?|\\d+[、.]|第[一二三四五六七八九十\\d]+[章节])\\s*.*",
            Pattern.MULTILINE);

    // 内部标题信息类（统一标准与自定义标题）
    private record HeadingInfo(int level, String text, int startOffset) {}

    @RagTraceNode(name = "分块", type = "上传管道", taskIdArg = "etlNode")
    public List<Document> execute(Document document, List<Integer> upChunks) {
        log.info("chunk start");
        if (document == null) {
            log.warn("document 为 null，跳过分块");
            return List.of();
        }
        String text = document.getText();
        if (!StringUtils.hasText(text)) return List.of();

        log.info("开始混合分块 | 长度:{}", text.length());

        // 全文短文本保护：若整个文档 token 很少，直接作为一个 chunk 返回
        if (countTokens(text) <= FULL_TEXT_SHORT_THRESHOLD) {
            Document singleChunk = createHierarchicalChunk("全文", text, document, null);
            return enrichChunkMetadataAndSkipChunk(List.of(singleChunk), document, upChunks);
        }

        // 可调参数（优先配置文件，缺省回退到代码默认值）
        int maxSize = propOrDefault(
                retrievalProperties == null ? null : retrievalProperties.getMaxChunkSize(),
                DEFAULT_MAX_CHUNK_SIZE);
        int minMergeSize = propOrDefault(
                retrievalProperties == null ? null : retrievalProperties.getMinMergeSize(),
                DEFAULT_MIN_MERGE_SIZE);
        int overlap = propOrDefault(
                retrievalProperties == null ? null : retrievalProperties.getChunkOverlap(),
                DEFAULT_CHUNK_OVERLAP);

        // 1. 执行分层+语义混合分块
        List<Document> allChunks = hybridChunking(text, document, maxSize, overlap);

        // 1.5 父子切片（small-to-big）：按章节聚合全文，作为 parent_text 附加到子块元数据
        attachParentText(allChunks);

        // 2. 最小块合并（token 维度，全局生效）
        allChunks = enforceMinTokenSize(allChunks, minMergeSize);

        // 3. 筛选并丰富元数据
        return enrichChunkMetadataAndSkipChunk(allChunks, document, upChunks);
    }

    // ==================== 配置辅助 ====================
    private static int propOrDefault(Integer value, int defaultValue) {
        return (value == null || value <= 0) ? defaultValue : value;
    }

    private double semanticThreshold() {
        if (retrievalProperties == null) return DEFAULT_SIMILARITY_THRESHOLD;
        double v = retrievalProperties.getSemanticSplitThreshold();
        return (v <= 0 || v >= 1) ? DEFAULT_SIMILARITY_THRESHOLD : v;
    }

    // ==================== 混合分块核心 ====================
    private List<Document> hybridChunking(String text, Document doc, int maxSize, int overlap) {
        // 提取所有标题（标准 Markdown + 自定义）
        List<HeadingInfo> headings = extractAllHeadings(text);
        if (!headings.isEmpty()) {
            return hierarchicalWithSemanticSplit(text, doc, maxSize, overlap, headings);
        } else {
            return paragraphWithSemanticSplit(text, doc, maxSize, overlap);
        }
    }

    /**
     * 分层骨架：按标题将文档分为若干节，每节内容若超过 maxSize 则调用语义分割。
     * 同时构建层级路径（section_path）。
     */
    private List<Document> hierarchicalWithSemanticSplit(String text, Document doc,
                                                         int maxSize, int overlap,
                                                         List<HeadingInfo> headings) {
        List<Integer> positions = headings.stream().map(HeadingInfo::startOffset).sorted().toList();
        List<Document> chunks = new ArrayList<>();
        Deque<String> pathStack = new ArrayDeque<>();   // 用于记录标题层级路径

        for (int i = 0; i < headings.size(); i++) {
            HeadingInfo heading = headings.get(i);
            int pos = heading.startOffset();
            int end = (i + 1 < positions.size()) ? positions.get(i + 1) : text.length();
            String section = text.substring(pos, end).trim();
            String title = heading.text();
            int level = heading.level();

            // 更新路径栈：移除比当前级别深的标题，然后压入当前标题
            while (!pathStack.isEmpty() && pathStack.size() >= level) {
                pathStack.pop();
            }
            pathStack.push(title);
            String fullPath = String.join(" > ", pathStack);

            if (section.isEmpty()) continue;

            if (countTokens(section) <= maxSize) {
                chunks.add(createHierarchicalChunk(title, section, doc, fullPath));
            } else {
                chunks.addAll(splitSectionWithSemantic(section, title, doc, maxSize, overlap, fullPath));
            }
        }
        return chunks;
    }

    /**
     * 无标题时：按空行自然段拆分，长段落启用语义分割。
     */
    private List<Document> paragraphWithSemanticSplit(String text, Document doc,
                                                      int maxSize, int overlap) {
        String[] paragraphs = text.split("\\n\\s*\\n");
        List<Document> chunks = new ArrayList<>();

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;
            int pt = countTokens(trimmed);

            if (pt <= maxSize) {
                if (pt < MIN_PARAGRAPH_TOKENS && !chunks.isEmpty()) {
                    Document last = chunks.removeLast();
                    String lastText = Objects.requireNonNullElse(last.getText(), "");
                    Document merged = new Document(lastText + "\n\n" + trimmed, last.getMetadata());
                    chunks.add(merged);
                } else {
                    chunks.add(createHierarchicalChunk("全文", trimmed, doc, null));
                }
            } else {
                chunks.addAll(splitLongParagraphSemantically(trimmed, "全文", doc, maxSize, overlap, null));
            }
        }
        return chunks;
    }

    /**
     * 对一个小节（标题下的内容）进行细粒度切割。
     */
    private List<Document> splitSectionWithSemantic(String section, String title,
                                                    Document doc, int maxSize, int overlap, String parentPath) {
        String[] paragraphs = section.split("\\n\\s*\\n");
        List<Document> subChunks = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;

        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;
            int pt = countTokens(trimmed);

            if (pt > maxSize) {
                if (!buffer.isEmpty()) {
                    subChunks.add(createHierarchicalChunk(title, buffer.toString().trim(), doc, parentPath));
                    buffer.setLength(0);
                    bufferTokens = 0;
                }
                subChunks.addAll(splitLongParagraphSemantically(trimmed, title, doc, maxSize, overlap, parentPath));
                continue;
            }

            if (bufferTokens + pt > maxSize && !buffer.isEmpty()) {
                subChunks.add(createHierarchicalChunk(title, buffer.toString().trim(), doc, parentPath));
                buffer.setLength(0);
                bufferTokens = 0;
            }

            if (!buffer.isEmpty()) buffer.append("\n\n");
            buffer.append(trimmed);
            bufferTokens += pt;
        }
        if (!buffer.isEmpty()) {
            subChunks.add(createHierarchicalChunk(title, buffer.toString().trim(), doc, parentPath));
        }
        return subChunks;
    }

    /**
     * 句向量批量计算：按 16 条/批拆分（低于各兼容端点 20 条上限），失败批回退零向量，
     * 保证语义分块在任意句子数下不因 embedding 批量限制而失败。
     */
    private List<float[]> embedInBatches(List<String> sentences) {
        int batch = 16;
        List<float[]> out = new ArrayList<>(sentences.size());
        for (int from = 0; from < sentences.size(); from += batch) {
            int end = Math.min(from + batch, sentences.size());
            List<String> part = sentences.subList(from, end);
            try {
                List<float[]> vecs = embeddingModel.embed(part);
                if (vecs != null && vecs.size() == part.size()) {
                    out.addAll(vecs);
                    continue;
                }
                throw new IllegalStateException("embedding 返回数量不匹配: " + (vecs == null ? "null" : vecs.size()));
            } catch (Exception e) {
                log.warn("语义分块批量嵌入失败，该批降级零向量: {} - {}", from, end, e.getMessage());
                for (int k = from; k < end; k++) {
                    out.add(new float[0]);
                }
            }
        }
        return out;
    }

    /**
     * 语义分割一个长段落：利用句子向量相似度决定切割点。
     */
    private List<Document> splitLongParagraphSemantically(String paragraph, String title,
                                                          Document doc, int maxSize, int overlap, String parentPath) {
        if (embeddingModel == null) {
            // 无嵌入模型降级为简单按句子长度分割
            return splitLongParagraphByLength(paragraph, title, doc, maxSize, parentPath);
        }

        List<String> sentences = splitSentences(paragraph, detectLanguage(paragraph));
        if (sentences.size() <= 1) {
            return List.of(createHierarchicalChunk(title, paragraph, doc, parentPath));
        }

        // 获取所有句子的嵌入向量（分批：Dashscope/Qwen 兼容端点单次 input 上限 20 条）
        List<float[]> embeddings = embedInBatches(sentences);

        // 基于相似度与大小进行合并
        List<Document> chunks = new ArrayList<>();
        List<String> buffer = new ArrayList<>();
        int bufferTokens = 0;
        float[] prevEmb = null;

        for (int i = 0; i < sentences.size(); i++) {
            String sent = sentences.get(i);
            int sentTokens = countTokens(sent);
            float[] curEmb = embeddings.get(i);

            if (buffer.isEmpty()) {
                buffer.add(sent);
                bufferTokens = sentTokens;
                prevEmb = curEmb;
                continue;
            }

            double similarity = cosineSimilarity(prevEmb, curEmb);
            boolean semanticBreak = similarity < semanticThreshold();
            boolean sizeBreak = (bufferTokens + sentTokens > maxSize);

            if (semanticBreak || sizeBreak) {
                chunks.add(createHierarchicalChunk(title, String.join(" ", buffer), doc, parentPath));
                buffer.clear();
                bufferTokens = 0;
            }
            buffer.add(sent);
            bufferTokens += sentTokens;
            prevEmb = curEmb;
        }
        if (!buffer.isEmpty()) {
            chunks.add(createHierarchicalChunk(title, String.join(" ", buffer), doc, parentPath));
        }

        // 添加重叠（句子级向前重叠）
        return enforceOverlap(chunks, overlap, maxSize);
    }

    /**
     * 降级方案：无嵌入模型时，按句子长度硬切割。
     */
    private List<Document> splitLongParagraphByLength(String paragraph, String title,
                                                      Document doc, int maxSize, String parentPath) {
        List<String> sentences = splitSentences(paragraph, detectLanguage(paragraph));
        List<String> groups = groupSentences(sentences, maxSize);
        return groups.stream()
                .map(g -> createHierarchicalChunk(title, g, doc, parentPath))
                .toList();
    }

    // ==================== 块构建辅助方法（支持层级路径） ====================
    private Document createHierarchicalChunk(String title, String content, Document doc, String parentPath) {
        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
        meta.put("section_title", title);
        // 如果有父级路径则使用，否则用标题本身
        meta.put("section_path", (parentPath != null && !parentPath.isBlank()) ? parentPath : title);
        meta.put("chunk_type", "hybrid");
        return new Document(content, meta);
    }

    /**
     * 父子切片：同一 section_path 的子块全文聚合为 parent_text，写入每个子块元数据。
     * <p>检索/精排仍用子块（匹配更准），生成阶段由 ParentExpandPostProcessor 展开父块（上下文更全）。</p>
     * <p>parent_text 截断至 1800 字符（Milvus metadata 字符串上限 2000）。</p>
     */
    private void attachParentText(List<Document> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        Map<String, StringBuilder> sectionTexts = new LinkedHashMap<>();
        for (Document chunk : chunks) {
            Map<String, Object> meta = chunk.getMetadata();
            if (meta == null) continue;
            String path = Objects.toString(meta.get("section_path"), "全文");
            sectionTexts.computeIfAbsent(path, k -> new StringBuilder())
                    .append(Objects.requireNonNullElse(chunk.getText(), "")).append("\n\n");
        }
        for (Document chunk : chunks) {
            Map<String, Object> meta = chunk.getMetadata();
            if (meta == null) continue;
            String path = Objects.toString(meta.get("section_path"), "全文");
            StringBuilder sb = sectionTexts.get(path);
            if (sb == null) continue;
            String parentText = sb.toString().strip();
            if (parentText.length() > 1800) {
                parentText = parentText.substring(0, 1800);
            }
            meta.put("parent_text", parentText);
        }
    }

    // ==================== 句子分割、语言检测、相似度等（与之前相同，略作调整） ====================
    private List<String> splitSentences(String text, String lang) {
        Pattern pattern = switch (lang) {
            case "zh" -> SENT_CN;
            case "en" -> SENT_EN;
            default -> SENT_MIXED;
        };
        return Arrays.stream(pattern.split(text))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String detectLanguage(String text) {
        long chinese = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count();
        long letters = text.chars().filter(Character::isLetter).count();
        if (letters == 0) return "mixed";
        return (chinese * 1.0 / letters) > 0.3 ? "zh" : "en";
    }

    private List<String> groupSentences(List<String> sentences, int maxTokens) {
        List<String> chunks = new ArrayList<>();
        int i = 0;
        while (i < sentences.size()) {
            StringBuilder sb = new StringBuilder();
            int curTokens = 0;
            while (i < sentences.size()) {
                String sent = sentences.get(i);
                int st = countTokens(sent);
                if (curTokens == 0 || curTokens + st <= maxTokens) {
                    if (curTokens > 0) sb.append(' ');
                    sb.append(sent);
                    curTokens += st;
                    i++;
                } else break;
            }
            if (!sb.isEmpty()) chunks.add(sb.toString().trim());
        }
        return chunks;
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-8);
    }

    // ==================== 重叠处理（仅在语义分割后使用） ====================
    private List<Document> enforceOverlap(List<Document> chunks, int overlapTokens, int maxSize) {
        if (overlapTokens <= 0 || chunks.size() <= 1) return chunks;
        List<Document> result = new ArrayList<>();
        List<String> prevSentences = null;

        for (Document chunk : chunks) {
            String chunkText = Objects.requireNonNullElse(chunk.getText(), "");
            List<String> curSentences = new ArrayList<>(splitSentences(chunkText, detectLanguage(chunkText)));

            if (prevSentences != null) {
                List<String> overlap = new ArrayList<>();
                int sum = 0;
                for (int i = prevSentences.size() - 1; i >= 0; i--) {
                    String s = prevSentences.get(i);
                    int st = countTokens(s);
                    if (sum + st > overlapTokens) break;
                    overlap.addFirst(s);
                    sum += st;
                }
                if (!overlap.isEmpty()) {
                    List<String> merged = new ArrayList<>(overlap);
                    merged.addAll(curSentences);
                    curSentences = merged;
                }
            }

            String newText = String.join(" ", curSentences);
            while (countTokens(newText) > maxSize && curSentences.size() > 1) {
                curSentences.removeLast();
                newText = String.join(" ", curSentences);
            }
            Document overlapChunk = new Document(newText, chunk.getMetadata());
            result.add(overlapChunk);
            prevSentences = new ArrayList<>(splitSentences(chunkText, detectLanguage(chunkText)));
        }
        return result;
    }

    // ==================== 最小块合并（以 token 数为准） ====================
    private List<Document> enforceMinTokenSize(List<Document> chunks, int minTokens) {
        if (chunks == null || chunks.isEmpty()) return new ArrayList<>();
        if (chunks.size() == 1) return chunks;

        List<Document> result = new ArrayList<>();
        Document current = chunks.getFirst();
        for (int i = 1; i < chunks.size(); i++) {
            Document next = chunks.get(i);
            int currentTokens = countTokens(Objects.requireNonNullElse(current.getText(), ""));
            boolean canMerge = currentTokens < minTokens;
            if (canMerge) {
                String nextText = Objects.requireNonNullElse(next.getText(), "");
                current = new Document(
                        Objects.requireNonNullElse(current.getText(), "") + "\n" + nextText,
                        current.getMetadata());
            } else {
                result.add(current);
                current = next;
            }
        }

        int currentTokens = countTokens(Objects.requireNonNullElse(current.getText(), ""));
        if (currentTokens < minTokens && !result.isEmpty()) {
            Document last = result.removeLast();
            String lastText = Objects.requireNonNullElse(last.getText(), "");
            current = new Document(lastText + "\n" + Objects.requireNonNullElse(current.getText(), ""),
                    last.getMetadata());
        }
        result.add(current);
        return result;
    }

    // ==================== 标题提取（标准 + 自定义） ====================
    private List<HeadingInfo> extractAllHeadings(String text) {
        List<HeadingInfo> headings = new ArrayList<>();

        // 1. 标准 Markdown 标题
        Node doc = MARKDOWN_PARSER.parse(text);
        for (Node node = doc.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading h) {
                int offset = getStartOffset(h, text);
                String title = getLiteralContent(h);
                headings.add(new HeadingInfo(h.getLevel(), title, offset));
            }
        }

        // 2. 自定义中文数字/章节标题
        Matcher matcher = CUSTOM_HEADING.matcher(text);
        while (matcher.find()) {
            int pos = matcher.start();
            String line = matcher.group().trim();
            // 避免与 Markdown 标题重复（若同一位置已有标准标题则跳过）
            if (headings.stream().anyMatch(h -> h.startOffset() == pos)) continue;
            // 简单推断级别：单行中文数字标题通常为二级，章节为一级
            int level = line.startsWith("第") ? 1 : 2;
            headings.add(new HeadingInfo(level, line, pos));
        }

        // 按位置排序
        headings.sort(Comparator.comparingInt(HeadingInfo::startOffset));
        return headings;
    }

    // ==================== Markdown 解析与标题偏移量 ====================
    private List<Heading> extractHeadings(String markdown) {
        List<Heading> headings = new ArrayList<>();
        Node doc = MARKDOWN_PARSER.parse(markdown);
        for (Node node = doc.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading h) headings.add(h);
        }
        return headings;
    }

    private String getLiteralContent(Node node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            switch (child) {
                case Text t -> sb.append(t.getLiteral());
                case Code c -> sb.append(c.getLiteral());
                case Link l -> sb.append(l.getTitle() != null ? l.getTitle() : l.getDestination());
                default -> sb.append(getLiteralContent(child));
            }
        }
        return sb.toString().trim();
    }

    private int getStartOffset(Node node, String fullText) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans != null && !spans.isEmpty()) {
            SourceSpan span = spans.getFirst();
            int lineIndex = span.getLineIndex();
            int columnIndex = span.getColumnIndex();
            int lineStart = 0;
            int currentLine = 0;
            for (int i = 0; i < fullText.length(); i++) {
                if (currentLine == lineIndex) {
                    lineStart = i;
                    break;
                }
                if (fullText.charAt(i) == '\n') currentLine++;
            }
            return lineStart + columnIndex;
        }
        String content = getLiteralContent(node);
        if (!content.isEmpty()) {
            int idx = fullText.indexOf(content);
            if (idx >= 0) return idx;
        }
        return 0;
    }

    // ==================== 元数据富化与筛选 ====================
    private List<Document> enrichChunkMetadataAndSkipChunk(List<Document> chunks,
                                                           Document document,
                                                           List<Integer> upChunks) {
        if (chunks == null || chunks.isEmpty()) return Collections.emptyList();
        String fileId = document.getId();
        boolean skipEnabled = (upChunks != null && !upChunks.isEmpty());
        List<Document> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            if (!skipEnabled || upChunks.contains(i)) {
                Document chunk = chunks.get(i);
                String fileChunkId = fileId + ":" + String.format("%06d", i + 1);
                Document enriched = chunk.mutate()
                        .id(fileChunkId)
                        .metadata("chunkSize", skipEnabled ? upChunks.size() : chunks.size())
                        .build();
                result.add(enriched);
            }
        }
        return result;
    }

    // ==================== Token 计数 ====================
    public interface TokenCounter {
        int count(String text);
    }

    public static class DefaultTokenCounter implements TokenCounter {
        @Override
        public int count(String text) {
            if (!StringUtils.hasText(text)) return 0;
            long chinese = text.chars()
                    .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count();
            long other = text.chars()
                    .filter(c -> !Character.isWhitespace(c) &&
                            Character.UnicodeScript.of(c) != Character.UnicodeScript.HAN)
                    .count();
            return (int) (chinese / 2 + other / 4);
        }
    }

    private int countTokens(String text) {
        return tokenCounter.count(text);
    }
}