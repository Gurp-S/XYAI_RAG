package com.XYai.myai.rag.etlpipeline.nodes;

import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.rag.etlpipeline.pojo.NodeConfig;
import com.XYai.myai.rag.etlpipeline.pojo.NodeResult;
import com.XYai.myai.rag.etlpipeline.pojo.PipelineProperties;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
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
import java.util.regex.Pattern;

@Slf4j
@Component
public class Chunker implements Ingestion {

    // ==================== 外部可注入组件 ====================
    @Resource
    private EmbeddingModel embeddingModel;          // Spring AI 嵌入模型，返回 List<float[]>
    @Resource
    private PipelineProperties pipelineProperties;

    private TokenCounter tokenCounter = new DefaultTokenCounter();

    public void setTokenCounter(TokenCounter tokenCounter) {
        this.tokenCounter = tokenCounter;
    }

    // ==================== Markdown 解析器 ====================
    private static final Parser MARKDOWN_PARSER = Parser.builder()
            .extensions(List.of(TablesExtension.create(), StrikethroughExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();

    // ==================== 句子边界 ====================
    private static final Pattern SENT_CN = Pattern.compile("(?<=[。！？])");
    private static final Pattern SENT_EN = Pattern.compile("(?<=[.!?])\\s+");
    private static final Pattern SENT_MIXED = Pattern.compile("(?<=[。！？.!?])\\s*");

    // ==================== 逻辑连接词 ====================
    private static final Set<String> LOGICAL_CONNECTORS = Set.of(
            "因此", "所以", "然而", "但是", "不过", "而且", "此外",
            "例如", "比如", "也就是说", "换言之", "同时", "另外",
            "总之", "综上所述", "由此可见", "并且", "不仅", "虽然", "即使"
    );

    // ==================== 标题匹配正则 ====================
    private static final Pattern CHINESE_NUM_TITLE = Pattern.compile("^[一二三四五六七八九十]+[、.]\\s*.*");
    private static final Pattern NUMBER_TITLE = Pattern.compile("^\\d+[、.]\\s+.*");
    private static final Pattern CHAPTER_TITLE = Pattern.compile("^第[一二三四五六七八九十\\d]+[章节].*");

    // ==================== 主入口 ====================
    @Override
    public String getNodeType() {
        return "chunker";
    }

    @RagTraceNode(name = "分块", type = "上传管道", taskIdArg = "etlNode")
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        Document sourceDoc = context.getDocument();
        if (sourceDoc == null || !StringUtils.hasText(sourceDoc.getText())) {
            return NodeResult.fail("文档内容为空");
        }

        String text = sourceDoc.getText();
        log.info("开始分块 | 长度:{}", text.length());

        JsonNode settings = Optional.ofNullable(config).map(NodeConfig::getSettings).orElse(null);
        int maxChunkSize = readInt(settings, "chunkSize", pipelineProperties.getTextChunkSize());
        int minMergeSize = readInt(settings, "minMergeSize", pipelineProperties.getMinChunkSizeChars());
        int maxNumChunks = readInt(settings, "maxNumChunks", pipelineProperties.getMaxNumChunks());
        int chunkOverlap = readInt(settings, "chunkOverlap", 0);
        boolean enableDensity = readBoolean(settings, "enableDensity", false);

        ChunkStrategy strategy = determineStrategy(text, settings);
        log.info("自动选择分块策略: {}", strategy);

        List<Document> allChunks = switch (strategy) {
            case SMART -> smartStrategy(text, sourceDoc, maxChunkSize, minMergeSize, chunkOverlap, enableDensity);
            case TITLE -> titleStrategy(text, sourceDoc, settings, maxChunkSize, minMergeSize);
            case PARENT_CHILD -> parentChildStrategy(text, sourceDoc, settings, minMergeSize, maxNumChunks);
            case REGEX -> regexStrategy(text, sourceDoc, settings, maxChunkSize, minMergeSize, maxNumChunks);
            case SEMANTIC -> semanticStrategy(text, sourceDoc, maxChunkSize, minMergeSize, chunkOverlap);
        };

        allChunks = enforceMinChunkSize(allChunks, minMergeSize, strategy != ChunkStrategy.TITLE);
        allChunks = enrichChunkMetadata(allChunks, sourceDoc);
        skipChunk(context);

        Document updatedDoc = sourceDoc.mutate()
                .metadata("chunk_total", allChunks.size())
                .metadata("chunk_strategy", strategy.name())
                .metadata("chunk_max_size", maxChunkSize)
                .build();
        context.setDocument(updatedDoc);
        context.setChunks(allChunks);

        log.info("分块完成 | 策略:{} | 总块数:{}", strategy, allChunks.size());
        return NodeResult.ok("分块成功，生成" + allChunks.size() + "个文档块");
    }

    // ==================== 策略决策 ====================
    private ChunkStrategy determineStrategy(String text, JsonNode settings) {
        if (settings != null && settings.has("strategy")) {
            ChunkStrategy s = ChunkStrategy.from(settings.get("strategy").asText());
            if (s != null) return s;
        }

        List<Heading> headings = extractHeadings(text);
        int headingCount = headings.size();
        int maxDepth = computeHeadingDepth(headings);
        boolean hasCustomRegex = settings != null && settings.has("regexPattern");

        if (hasCustomRegex && headingCount < 3) return ChunkStrategy.REGEX;
        if (embeddingModel != null && headingCount < 10 && text.length() < 10_000) {
            return ChunkStrategy.SEMANTIC;
        }
        if (headingCount >= 12 && maxDepth >= 3) return ChunkStrategy.PARENT_CHILD;
        if (headingCount >= 4 && maxDepth >= 2) return ChunkStrategy.TITLE;
        return ChunkStrategy.SMART;
    }

    // ==================== 四大原有策略（实现稍作增强） ====================
    private List<Document> smartStrategy(String text, Document doc, int maxSize, int minSize,
                                         int overlap, boolean enableDensity) {
        List<SemanticBlock> blocks = parseMarkdownToSemanticBlocks(text);
        return smartGreedyMerge(blocks, doc, maxSize, minSize, overlap, enableDensity);
    }

    private List<Document> titleStrategy(String text, Document doc, JsonNode settings, int maxSize, int minSize) {
        int splitLevel = readInt(settings, "splitLevel", 2);
        List<Document> chunks = buildTitleChunks(text, doc, splitLevel, maxSize);
        return enforceMinChunkSize(chunks, minSize, false);
    }

    private List<Document> parentChildStrategy(String text, Document doc, JsonNode settings, int minSize, int maxNum) {
        int parentSize = readInt(settings, "parentChunkSize", 2048);
        int childSize = readInt(settings, "childChunkSize", 512);
        int splitLevel = readInt(settings, "parentSplitLevel", 2);
        return processParentChild(text, doc, parentSize, childSize, splitLevel, minSize, maxNum);
    }

    private List<Document> regexStrategy(String text, Document doc, JsonNode settings, int maxSize, int minSize, int maxNum) {
        Pattern regex = Optional.ofNullable(getCustomRegex(settings)).orElse(Pattern.compile("\\n\\s*\\n"));
        List<String> raw = splitByRegex(text, regex);
        return applyTokenSizeControl(raw, doc, maxSize, minSize, maxNum);
    }

    // ==================== 语义分块（直接使用 EmbeddingModel 返回 List<float[]>） ====================
    private List<Document> semanticStrategy(String text, Document doc, int maxSize, int minSize, int overlap) {
        if (embeddingModel == null) {
            log.warn("EmbeddingModel 不可用，语义分块降级为 SMART");
            return smartStrategy(text, doc, maxSize, minSize, overlap, false);
        }

        // 获取所有句子（保留标题结构）
        List<SemanticBlock> blocks = parseMarkdownToSemanticBlocks(text);
        List<String> sentences = new ArrayList<>();
        for (SemanticBlock block : blocks) {
            if (block.nodeType == Heading.class) {
                sentences.add(block.headingTitle);
            } else {
                sentences.addAll(splitSentences(block.content, detectLanguage(block.content)));
            }
        }
        if (sentences.isEmpty()) return List.of();

        // 批量获取嵌入向量（直接返回 List<float[]>）
        List<float[]> embeddings = embeddingModel.embed(sentences);

        // 基于余弦相似度合并
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
            boolean semanticBreak = similarity < 0.5;          // 阈值可配置
            boolean sizeBreak = (bufferTokens + sentTokens > maxSize);

            if (semanticBreak || sizeBreak) {
                chunks.add(buildChunkFromSentences(buffer, doc));
                buffer.clear();
                bufferTokens = 0;
            }
            buffer.add(sent);
            bufferTokens += sentTokens;
            prevEmb = curEmb;
        }
        if (!buffer.isEmpty()) {
            chunks.add(buildChunkFromSentences(buffer, doc));
        }

        return enforceOverlap(chunks, overlap, maxSize);
    }

    private Document buildChunkFromSentences(List<String> sentences, Document sourceDoc) {
        String text = String.join(" ", sentences);
        Map<String, Object> meta = new HashMap<>(sourceDoc.getMetadata());
        meta.put("chunk_type", "semantic");
        return new Document(text, meta);
    }

    // ==================== 智能合并（支持密度调节） ====================
    private List<Document> smartGreedyMerge(List<SemanticBlock> blocks, Document doc,
                                            int maxSize, int minMergeSize,
                                            int overlap, boolean enableDensity) {
        List<Document> chunks = new ArrayList<>();
        List<SemanticBlock> buffer = new ArrayList<>();
        int bufferTokens = 0;
        int effectiveMax = maxSize;

        for (SemanticBlock block : blocks) {
            if (enableDensity) {
                double density = estimateDensity(block);
                effectiveMax = (int) (maxSize * (1.0 + (1.0 - density)));
            }

            if (block.nodeType == Heading.class && bufferTokens >= minMergeSize) {
                chunks.add(buildChunkFromBuffer(buffer, doc));
                buffer.clear();
                bufferTokens = 0;
            }

            int bt = block.tokenCount;
            if (buffer.isEmpty()) {
                buffer.add(block);
                bufferTokens = bt;
                continue;
            }
            if (bufferTokens + bt <= effectiveMax) {
                buffer.add(block);
                bufferTokens += bt;
            } else {
                chunks.add(buildChunkFromBuffer(buffer, doc));
                buffer.clear();
                buffer.add(block);
                bufferTokens = bt;
            }
        }
        if (!buffer.isEmpty()) {
            chunks.add(buildChunkFromBuffer(buffer, doc));
        }

        // 大块句子拆分
        List<Document> finalChunks = new ArrayList<>();
        for (Document c : chunks) {
            int tokens = countTokens(c.getText());
            int charLen = c.getText().length();
            if (tokens > maxSize || charLen > maxSize * 3L) {
                finalChunks.addAll(splitLargeChunk(c, maxSize));
            } else {
                finalChunks.add(c);
            }
        }

        List<Document> merged = enforceMinChunkSize(finalChunks, minMergeSize, true);
        return enforceOverlap(merged, overlap, maxSize);
    }

    private Document buildChunkFromBuffer(List<SemanticBlock> blocks, Document doc) {
        StringBuilder content = new StringBuilder();
        String title = "";
        List<String> breadcrumbs = new ArrayList<>();
        for (SemanticBlock block : blocks) {
            content.append(block.content).append("\n\n");
            if (block.nodeType == Heading.class && title.isEmpty()) {
                title = block.headingTitle;
                breadcrumbs.add(title);
            }
        }
        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
        meta.put("section_title", title);
        meta.put("section_path", String.join(" > ", breadcrumbs));
        return new Document(content.toString().trim(), meta);
    }

    // ==================== 重叠处理 ====================
    private List<Document> enforceOverlap(List<Document> chunks, int overlapTokens, int maxSize) {
        if (overlapTokens <= 0 || chunks.size() <= 1) return chunks;
        List<Document> result = new ArrayList<>();
        List<String> prevSentences = null;
        for (Document chunk : chunks) {
            List<String> curSentences = splitSentences(chunk.getText(), detectLanguage(chunk.getText()));
            if (prevSentences != null) {
                List<String> overlap = new ArrayList<>();
                int sum = 0;
                for (int i = prevSentences.size() - 1; i >= 0; i--) {
                    String s = prevSentences.get(i);
                    int st = countTokens(s);
                    if (sum + st > overlapTokens) break;
                    overlap.add(0, s);
                    sum += st;
                }
                if (!overlap.isEmpty()) {
                    List<String> newSents = new ArrayList<>(overlap);
                    newSents.addAll(curSentences);
                    curSentences = newSents;
                }
            }
            String newText = String.join(" ", curSentences);
            if (countTokens(newText) > maxSize) {
                List<String> trimmed = new ArrayList<>(curSentences);
                while (countTokens(String.join(" ", trimmed)) > maxSize && trimmed.size() > 1) {
                    trimmed.remove(trimmed.size() - 1);
                }
                newText = String.join(" ", trimmed);
            }
            Document overlapChunk = new Document(newText, chunk.getMetadata());
            result.add(overlapChunk);
            prevSentences = splitSentences(chunk.getText(), detectLanguage(chunk.getText()));
        }
        return result;
    }

    // ==================== Markdown 解析（表格/代码智能切割） ====================
    private List<SemanticBlock> parseMarkdownToSemanticBlocks(String markdown) {
        List<SemanticBlock> blocks = new ArrayList<>();
        Node document = MARKDOWN_PARSER.parse(markdown);

        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading h) {
                SemanticBlock b = new SemanticBlock();
                b.nodeType = Heading.class;
                b.headingLevel = h.getLevel();
                b.headingTitle = getLiteralContent(h);
                b.content = getRawText(node);
                b.tokenCount = countTokens(b.content);
                blocks.add(b);
            } else if (node instanceof FencedCodeBlock f) {
                blocks.addAll(splitCodeBlock(f.getLiteral(), f.getInfo(), 30));
            } else if (node instanceof IndentedCodeBlock i) {
                blocks.addAll(splitCodeBlock(i.getLiteral(), "", 30));
            } else if (node instanceof TableBlock table) {
                blocks.addAll(splitTableBlock(table));
            } else if (node instanceof BlockQuote || node instanceof Paragraph) {
                SemanticBlock b = new SemanticBlock();
                b.nodeType = node.getClass();
                b.content = getRawText(node);
                b.tokenCount = countTokens(b.content);
                blocks.add(b);
            } else if (node instanceof ThematicBreak) {
                continue;
            } else {
                SemanticBlock b = new SemanticBlock();
                b.nodeType = node.getClass();
                b.content = getRawText(node);
                b.tokenCount = countTokens(b.content);
                blocks.add(b);
            }
        }

        if (blocks.size() <= 1 && markdown.length() > 100) {
            blocks = fallbackBlockSplit(markdown);
        }
        if (blocks.isEmpty() && StringUtils.hasText(markdown)) {
            SemanticBlock fallback = new SemanticBlock();
            fallback.nodeType = Paragraph.class;
            fallback.content = markdown;
            fallback.tokenCount = countTokens(markdown);
            blocks.add(fallback);
        }
        return blocks;
    }

    private List<SemanticBlock> splitTableBlock(TableBlock table) {
        List<SemanticBlock> result = new ArrayList<>();
        List<String> header = new ArrayList<>();
        boolean hasHeader = false;
        for (Node child = table.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof TableHead head) {
                for (Node rowNode = head.getFirstChild(); rowNode != null; rowNode = rowNode.getNext()) {
                    if (rowNode instanceof TableRow row) {
                        for (Node cellNode = row.getFirstChild(); cellNode != null; cellNode = cellNode.getNext()) {
                            if (cellNode instanceof TableCell cell) {
                                header.add(getLiteralContent(cell));
                            }
                        }
                        hasHeader = true;
                        break;
                    }
                }
            } else if (child instanceof TableRow row && hasHeader) {
                List<String> values = new ArrayList<>();
                for (Node cellNode = row.getFirstChild(); cellNode != null; cellNode = cellNode.getNext()) {
                    if (cellNode instanceof TableCell cell) {
                        values.add(getLiteralContent(cell));
                    }
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < Math.max(header.size(), values.size()); i++) {
                    String h = i < header.size() ? header.get(i) : "";
                    String v = i < values.size() ? values.get(i) : "";
                    sb.append(h).append(": ").append(v).append(" | ");
                }
                String content = sb.toString().replaceAll(" \\| $", "");
                SemanticBlock b = new SemanticBlock();
                b.nodeType = TableBlock.class;
                b.content = content;
                b.tokenCount = countTokens(content);
                result.add(b);
            }
        }
        return result.isEmpty() ? List.of(createGenericBlock(table)) : result;
    }

    private List<SemanticBlock> splitCodeBlock(String code, String language, int maxLines) {
        List<SemanticBlock> blocks = new ArrayList<>();
        String[] lines = code.split("\\R");
        int total = lines.length;
        int start = 0;
        while (start < total) {
            int end = Math.min(start + maxLines, total);
            String chunkCode = String.join("\n", Arrays.copyOfRange(lines, start, end));
            String fence = "```" + (language != null ? language : "") + "\n" + chunkCode + "\n```";
            SemanticBlock b = new SemanticBlock();
            b.nodeType = FencedCodeBlock.class;
            b.content = fence;
            b.tokenCount = countTokens(fence);
            blocks.add(b);
            start = end;
        }
        return blocks;
    }

    private SemanticBlock createGenericBlock(Node node) {
        SemanticBlock b = new SemanticBlock();
        b.nodeType = node.getClass();
        b.content = getRawText(node);
        b.tokenCount = countTokens(b.content);
        return b;
    }

    // ==================== 句子与语言检测 ====================
    private List<String> splitSentences(String text, String lang) {
        Pattern pattern = switch (lang) {
            case "zh" -> SENT_CN;
            case "en" -> SENT_EN;
            default -> SENT_MIXED;
        };
        return Arrays.stream(pattern.split(text))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private String detectLanguage(String text) {
        long chinese = text.chars()
                .filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN).count();
        long letters = text.chars().filter(Character::isLetter).count();
        if (letters == 0) return "mixed";
        return (chinese * 1.0 / letters) > 0.3 ? "zh" : "en";
    }

    // ==================== Token 计数接口及默认实现 ====================
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

    // ==================== 余弦相似度（直接使用 float[]） ====================
    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB) + 1e-8);
    }

    // ==================== 其他辅助方法（保持原有逻辑） ====================
    private List<SemanticBlock> fallbackBlockSplit(String text) {
        List<SemanticBlock> blocks = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");
        String lastTitle = "";
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;
            SemanticBlock block = new SemanticBlock();
            String firstLine = trimmed.split("\\n")[0].trim();
            if (looksLikeTitle(firstLine)) {
                block.nodeType = Heading.class;
                block.headingLevel = 2;
                String cleanTitle = firstLine.replaceAll("[*#]", "").trim();
                block.headingTitle = cleanTitle;
                lastTitle = cleanTitle;
            } else {
                block.nodeType = Paragraph.class;
            }
            block.content = trimmed;
            block.tokenCount = countTokens(trimmed);
            blocks.add(block);
        }
        return blocks;
    }

    private boolean looksLikeTitle(String line) {
        if (line == null || line.isEmpty()) return false;
        String stripped = line.strip();
        if (stripped.startsWith("#")) return true;
        if (stripped.startsWith("**") && stripped.endsWith("**")
                && stripped.length() > 4 && !stripped.substring(2, stripped.length() - 2).contains("\n"))
            return true;
        if (stripped.matches("^[一二三四五六七八九十\\d]+[、.．]\\s*.*")) return true;
        long letterCount = stripped.chars().filter(Character::isLetter).count();
        if (letterCount >= 3) {
            long upperCount = stripped.chars().filter(Character::isUpperCase).count();
            if (upperCount > letterCount * 0.8) return true;
        }
        return stripped.length() >= 3 && stripped.length() <= 40
                && !stripped.matches(".*[。！？.!?].*")
                && !stripped.contains("：") && !stripped.contains(":")
                && !stripped.contains("，") && !stripped.contains(",")
                && !stripped.contains("、")
                && (containsChinese(stripped) || stripped.chars().anyMatch(Character::isUpperCase));
    }

    private List<Document> splitLargeChunk(Document chunk, int maxSize) {
        String text = chunk.getText();
        List<String> sentences = splitSentences(text, detectLanguage(text));
        sentences = mergeLogicalSentences(sentences);
        List<String> groups = groupSentences(sentences, maxSize);
        return groups.stream().map(g -> new Document(g, chunk.getMetadata())).toList();
    }

    private List<String> mergeLogicalSentences(List<String> sentences) {
        if (sentences.size() <= 1) return sentences;
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder(sentences.get(0));
        for (int i = 1; i < sentences.size(); i++) {
            String s = sentences.get(i);
            if (startsWithLogicalConnector(s)) {
                current.append(' ').append(s);
            } else {
                merged.add(current.toString());
                current = new StringBuilder(s);
            }
        }
        merged.add(current.toString());
        return merged;
    }

    private boolean startsWithLogicalConnector(String s) {
        return LOGICAL_CONNECTORS.stream().anyMatch(s::startsWith);
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
                    if (curTokens > 0) { sb.append(' '); curTokens++; }
                    sb.append(sent);
                    curTokens += st;
                    i++;
                } else break;
            }
            if (!sb.isEmpty()) chunks.add(sb.toString().trim());
        }
        return chunks;
    }

    // ==================== 元数据富化 ====================
    private List<Document> enrichChunkMetadata(List<Document> chunks, Document sourceDoc) {
        if (chunks.isEmpty()) return Collections.emptyList();
        String fileId = sourceDoc.getMetadata().getOrDefault("fileId", "default").toString();
        List<Document> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            if (!StringUtils.hasText(chunk.getText())) continue;
            Map<String, Object> meta = new HashMap<>(chunk.getMetadata());
            meta.put("chunkId", i + 1);
            meta.put("chunkSize", chunks.size());
            meta.put("chunk_token_count", countTokens(chunk.getText()));
            String chunkId = fileId + ":" + String.format("%06d", i + 1);
            result.add(Document.builder().id(chunkId).text(chunk.getText()).metadata(meta).build());
        }
        return result;
    }

    private void skipChunk(IngestionContext context) {
        try {
            Object chunkCopy = context.getDocument().getMetadata().get(IngestionContext.META_SKIP_CHUNK);
            if (chunkCopy != null) {
                List<Integer> keepChunkList = new ArrayList<>();
                if (chunkCopy instanceof Collection<?> col) {
                    for (Object item : col) {
                        if (item instanceof Number n) keepChunkList.add(n.intValue());
                    }
                }
                if (!keepChunkList.isEmpty()) {
                    List<Document> original = context.getChunks();
                    List<Document> filtered = new ArrayList<>();
                    for (Document chunk : original) {
                        int chunkId = Integer.parseInt(chunk.getId().split(":")[1]);
                        if (keepChunkList.contains(chunkId)) filtered.add(chunk);
                    }
                    Document updated = context.getDocument().mutate()
                            .metadata(IngestionContext.META_CHUNK_SIZE, filtered.size())
                            .build();
                    context.setDocument(updated);
                    context.setChunks(filtered);
                    log.info("分块跳过: 原始{}块 -> 保留{}块", original.size(), filtered.size());
                }
            }
        } catch (Exception e) {
            log.debug("skipChunk 异常", e);
        }
    }

    // ==================== 标题提取 / 层级计算 ====================
    private List<Heading> extractHeadings(String markdown) {
        List<Heading> headings = new ArrayList<>();
        Node doc = MARKDOWN_PARSER.parse(markdown);
        for (Node node = doc.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading h) headings.add(h);
        }
        return headings;
    }

    private int computeHeadingDepth(List<Heading> headings) {
        if (headings.isEmpty()) return 0;
        Deque<Integer> stack = new ArrayDeque<>();
        int maxDepth = 1;
        for (Heading h : headings) {
            int level = h.getLevel();
            while (!stack.isEmpty() && stack.peek() >= level) stack.pop();
            stack.push(level);
            maxDepth = Math.max(maxDepth, stack.size());
        }
        return maxDepth;
    }

    private String getLiteralContent(Node node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text t) sb.append(t.getLiteral());
            else if (child instanceof Code c) sb.append(c.getLiteral());
            else if (child instanceof Link l) sb.append(l.getTitle() != null ? l.getTitle() : l.getDestination());
            else sb.append(getLiteralContent(child));
        }
        return sb.toString().trim();
    }

    private String getRawText(Node node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();
        Node child = node.getFirstChild();
        while (child != null) {
            if (child instanceof Text t) sb.append(t.getLiteral());
            else if (child instanceof Code c) sb.append('`').append(c.getLiteral()).append('`');
            else if (child instanceof Emphasis e) sb.append('*').append(getRawText(child)).append('*');
            else if (child instanceof StrongEmphasis se) sb.append("**").append(getRawText(child)).append("**");
            else if (child instanceof SoftLineBreak || child instanceof HardLineBreak) sb.append('\n');
            else sb.append(getRawText(child));
            child = child.getNext();
        }
        return sb.toString().trim();
    }

    // ==================== 强制小块合并 ====================
    private List<Document> enforceMinChunkSize(List<Document> chunks, int minSize, boolean allowCrossSection) {
        if (chunks == null || chunks.isEmpty()) return new ArrayList<>();
        if (chunks.size() == 1) return chunks;
        List<Document> result = new ArrayList<>();
        Document current = chunks.get(0);
        for (int i = 1; i < chunks.size(); i++) {
            Document next = chunks.get(i);
            boolean canMerge = current.getText().length() < minSize
                    && (allowCrossSection || isSameSection(current, next));
            if (canMerge) {
                current = new Document(current.getText() + "\n" + next.getText(), current.getMetadata());
            } else {
                result.add(current);
                current = next;
            }
        }
        if (current.getText().length() < minSize && !result.isEmpty()) {
            Document last = result.remove(result.size() - 1);
            if (allowCrossSection || isSameSection(last, current)) {
                current = new Document(last.getText() + "\n" + current.getText(), last.getMetadata());
            } else {
                result.add(last);
            }
        }
        result.add(current);
        return result;
    }

    private boolean isSameSection(Document a, Document b) {
        String pa = (String) a.getMetadata().get("section_path");
        String pb = (String) b.getMetadata().get("section_path");
        if (pa == null || pb == null) return true;
        return pa.equals(pb) || pa.startsWith(pb + " >") || pb.startsWith(pa + " >");
    }

    // ==================== TITLE 策略相关 ====================
    private List<Document> buildTitleChunks(String markdown, Document doc, int splitLevel, int maxChunkSize) {
        List<Heading> all = extractHeadings(markdown);
        if (all.isEmpty()) return buildTitleChunksFallback(markdown, doc, maxChunkSize);
        List<Heading> boundaries = (splitLevel >= 6) ? all
                : all.stream().filter(h -> h.getLevel() <= splitLevel).toList();
        if (boundaries.isEmpty()) {
            return splitByBlankLinesToChunks(markdown, doc, maxChunkSize);
        }
        List<Integer> positions = boundaries.stream()
                .map(h -> getStartOffset(h, markdown))
                .sorted()
                .toList();
        List<Document> chunks = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < positions.size(); i++) {
            int pos = positions.get(i);
            if (pos > start) {
                String prefix = markdown.substring(start, pos).trim();
                if (!prefix.isEmpty()) {
                    chunks.addAll(splitLongContent(prefix, "前言", doc, maxChunkSize));
                }
            }
            int end = (i + 1 < positions.size()) ? positions.get(i + 1) : markdown.length();
            String content = markdown.substring(pos, end).trim();
            String title = getLiteralContent(boundaries.get(i));
            chunks.addAll(splitLongContent(content, title, doc, maxChunkSize));
            start = end;
        }
        return chunks;
    }

    private List<Document> buildTitleChunksFallback(String text, Document doc, int maxChunkSize) {
        List<Document> chunks = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");
        String currentTitle = "前言";
        StringBuilder buffer = new StringBuilder();
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.isEmpty()) continue;
            String firstLine = trimmed.split("\\n")[0].trim();
            if (looksLikeTitle(firstLine)) {
                if (!buffer.isEmpty()) {
                    chunks.add(createTitleChunk(currentTitle, buffer.toString().trim(), doc));
                    buffer.setLength(0);
                }
                currentTitle = firstLine.replaceAll("[*#]", "").trim();
                buffer.append(trimmed);
            } else {
                if (!buffer.isEmpty()) buffer.append("\n\n");
                buffer.append(trimmed);
            }
        }
        if (!buffer.isEmpty()) {
            chunks.add(createTitleChunk(currentTitle, buffer.toString().trim(), doc));
        }
        return chunks;
    }

    private List<Document> splitLongContent(String content, String title, Document doc, int maxChunkSize) {
        int maxChars = maxChunkSize * 3;
        if (content.length() <= maxChars) {
            return List.of(createTitleChunk(title, content, doc));
        }
        List<Document> subDocs = new ArrayList<>();
        String[] paragraphs = content.split("\\n\\s*\\n");
        StringBuilder buffer = new StringBuilder();
        int currentLen = 0;
        for (String para : paragraphs) {
            int paraLen = para.length();
            if (currentLen + paraLen > maxChars && !buffer.isEmpty()) {
                String last = lastParagraph(buffer.toString());
                if (!isHeadingLike(last)) {
                    subDocs.add(createTitleChunk(title, buffer.toString().trim(), doc));
                    buffer.setLength(0);
                    currentLen = 0;
                }
            }
            if (!buffer.isEmpty()) { buffer.append("\n\n"); currentLen += 2; }
            buffer.append(para);
            currentLen += paraLen;
        }
        if (!buffer.isEmpty()) {
            subDocs.add(createTitleChunk(title, buffer.toString().trim(), doc));
        }
        return subDocs;
    }

    private String lastParagraph(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] parts = text.split("\\n\\s*\\n");
        return parts[parts.length - 1].trim();
    }

    private boolean isHeadingLike(String line) {
        if (line == null || line.isEmpty()) return false;
        if (line.startsWith("#")) return true;
        if (line.matches("^[一二三四五六七八九十]+[、.]\\s*.*")) return true;
        if (line.matches("^\\d+[、.]\\s+.*")) return true;
        return line.matches("^第[一二三四五六七八九十\\d]+[章节].*");
    }

    private List<Document> splitByBlankLinesToChunks(String text, Document doc, int maxChunkSize) {
        return Arrays.stream(text.split("\\n\\s*\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .flatMap(part -> splitLongContent(part, "前言", doc, maxChunkSize).stream())
                .toList();
    }

    private Document createTitleChunk(String title, String content, Document doc) {
        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
        meta.put("section_title", title);
        meta.put("section_path", title);
        return new Document(content, meta);
    }

    // ==================== PARENT_CHILD 策略相关 ====================
    private List<Document> processParentChild(String markdown, Document doc,
                                              int parentSize, int childSize,
                                              int parentSplitLevel, int minMergeSize, int maxNumChunks) {
        List<Document> parents = generateParentChunks(markdown, doc, parentSize, parentSplitLevel, maxNumChunks);
        List<Document> children = new ArrayList<>();
        int remaining = maxNumChunks > 0 ? maxNumChunks : Integer.MAX_VALUE;
        for (Document parent : parents) {
            if (remaining <= 0) break;
            List<SemanticBlock> blocks = parseMarkdownToSemanticBlocks(parent.getText());
            List<Document> sub = smartGreedyMerge(blocks, doc, childSize, minMergeSize, 0, false);
            for (Document s : sub) {
                if (remaining <= 0) break;
                Map<String, Object> meta = new HashMap<>(s.getMetadata());
                meta.put("parent_chunk_id", parent.getId());
                meta.put("parent_title", parent.getMetadata().getOrDefault("section_title", ""));
                meta.put("chunk_type", "child");
                children.add(new Document(Objects.requireNonNull(s.getText()), meta));
                remaining--;
            }
        }
        return children;
    }

    private List<Document> generateParentChunks(String markdown, Document doc,
                                                int parentSize, int parentSplitLevel, int maxNumChunks) {
        List<Heading> all = extractHeadings(markdown);
        List<Heading> boundaries = all.stream().filter(h -> h.getLevel() <= parentSplitLevel).toList();
        if (boundaries.isEmpty()) {
            if (countTokens(markdown) <= parentSize) {
                return List.of(createParentChunk("全文", markdown, doc));
            } else {
                return splitLargeIntoParentChunks(markdown, parentSize, doc, "全文");
            }
        }
        List<Integer> positions = boundaries.stream()
                .map(h -> getStartOffset(h, markdown))
                .sorted()
                .toList();
        List<Document> parents = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            if (maxNumChunks > 0 && parents.size() >= maxNumChunks) break;
            int pos = positions.get(i);
            int end = (i + 1 < positions.size()) ? positions.get(i + 1) : markdown.length();
            String content = markdown.substring(pos, end).trim();
            String title = getLiteralContent(boundaries.get(i));
            if (countTokens(content) <= parentSize) {
                parents.add(createParentChunk(title, content, doc));
            } else {
                parents.addAll(splitLargeIntoParentChunks(content, parentSize, doc, title));
            }
        }
        return parents;
    }

    private Document createParentChunk(String title, String content, Document doc) {
        Map<String, Object> meta = new HashMap<>(doc.getMetadata());
        meta.put("section_title", title);
        meta.put("chunk_type", "parent");
        return Document.builder().id(UUID.randomUUID().toString()).text(content).metadata(meta).build();
    }

    private List<Document> splitLargeIntoParentChunks(String content, int maxTokens, Document doc, String title) {
        List<Document> chunks = new ArrayList<>();
        String[] paragraphs = content.split("\\n\\s*\\n");
        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;
        for (String para : paragraphs) {
            int pt = countTokens(para);
            if (bufferTokens + pt > maxTokens && !buffer.isEmpty()) {
                chunks.add(createParentChunk(title, buffer.toString().trim(), doc));
                buffer.setLength(0);
                bufferTokens = 0;
            }
            if (!buffer.isEmpty()) buffer.append("\n\n");
            buffer.append(para);
            bufferTokens += pt;
        }
        if (!buffer.isEmpty()) chunks.add(createParentChunk(title, buffer.toString().trim(), doc));
        return chunks;
    }

    // ==================== REGEX 策略相关 ====================
    private Pattern getCustomRegex(JsonNode settings) {
        if (settings == null || !settings.has("regexPattern")) return null;
        String regex = settings.get("regexPattern").asText();
        if (regex.isEmpty()) return null;
        return Pattern.compile(regex, Pattern.MULTILINE);
    }

    private List<String> splitByRegex(String text, Pattern regex) {
        return Arrays.stream(regex.split(text)).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private List<Document> applyTokenSizeControl(List<String> raw, Document doc, int maxSize, int minSize, int maxNum) {
        List<Document> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;
        for (String chunk : raw) {
            if (maxNum > 0 && result.size() >= maxNum) break;
            int ct = countTokens(chunk);
            if (ct < minSize) {
                if (buffer.isEmpty()) {
                    buffer.append(chunk);
                    bufferTokens = ct;
                } else if (bufferTokens + ct <= maxSize) {
                    buffer.append("\n\n").append(chunk);
                    bufferTokens += ct;
                } else {
                    result.add(new Document(buffer.toString().trim(), doc.getMetadata()));
                    buffer.setLength(0);
                    buffer.append(chunk);
                    bufferTokens = ct;
                }
                continue;
            }
            if (!buffer.isEmpty()) {
                result.add(new Document(buffer.toString().trim(), doc.getMetadata()));
                buffer.setLength(0);
                bufferTokens = 0;
            }
            if (ct <= maxSize) {
                result.add(new Document(chunk, doc.getMetadata()));
            } else {
                List<String> sub = splitLargeParagraph(chunk, maxSize);
                sub.forEach(s -> result.add(new Document(s, doc.getMetadata())));
            }
        }
        if (!buffer.isEmpty())
            result.add(new Document(buffer.toString().trim(), doc.getMetadata()));
        return enforceMinChunkSize(result, minSize, true);
    }

    private List<String> splitLargeParagraph(String paragraph, int maxSize) {
        List<String> sentences = splitSentences(paragraph, detectLanguage(paragraph));
        sentences = mergeLogicalSentences(sentences);
        return groupSentences(sentences, maxSize);
    }

    // ==================== 偏移量计算 ====================
    private int getStartOffset(Node node, String fullText) {
        List<SourceSpan> spans = node.getSourceSpans();
        if (spans != null && !spans.isEmpty()) {
            SourceSpan span = spans.get(0);
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

    // ==================== 工具方法 ====================
    private int readInt(JsonNode s, String key, int def) {
        if (s != null && s.has(key) && s.get(key).canConvertToInt()) return s.get(key).asInt();
        return def;
    }

    private boolean readBoolean(JsonNode s, String key, boolean def) {
        if (s != null && s.has(key) && s.get(key).isBoolean()) return s.get(key).asBoolean();
        return def;
    }

    private boolean containsChinese(String s) {
        return s.codePoints().anyMatch(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN);
    }

    private double estimateDensity(SemanticBlock block) {
        String text = block.content;
        if (text.isEmpty()) return 0.5;
        long meaningful = text.chars().filter(c -> !Character.isWhitespace(c) && !isPunctuation(c)).count();
        return (double) meaningful / text.length();
    }

    private boolean isPunctuation(int codePoint) {
        int type = Character.getType(codePoint);
        return type == Character.OTHER_PUNCTUATION ||
                type == Character.START_PUNCTUATION ||
                type == Character.END_PUNCTUATION ||
                type == Character.INITIAL_QUOTE_PUNCTUATION ||
                type == Character.FINAL_QUOTE_PUNCTUATION;
    }

    // ==================== 内部数据结构 ====================
    private static class SemanticBlock {
        Class<? extends Node> nodeType;
        String content = "";
        int headingLevel = 0;
        String headingTitle = "";
        int tokenCount = 0;
    }

    public enum ChunkStrategy {
        SMART, PARENT_CHILD, TITLE, REGEX, SEMANTIC;

        public static ChunkStrategy from(String name) {
            if (name == null || name.isBlank()) return null;
            try {
                return valueOf(name.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("未知分块策略: {}", name);
                return null;
            }
        }
    }
}