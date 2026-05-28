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
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Component
public class Chunker implements Ingestion {

    private static final Parser MARKDOWN_PARSER = Parser.builder()
            .extensions(List.of(
                    TablesExtension.create(),
                    StrikethroughExtension.create()))
            .includeSourceSpans(IncludeSourceSpans.BLOCKS)
            .build();
    // 句子边界与逻辑连接词
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？.!?])");
    private static final Set<String> LOGICAL_CONNECTORS = Set.of(
            "因此", "所以", "然而", "但是", "不过", "而且", "此外",
            "例如", "比如", "也就是说", "换言之", "同时", "另外",
            "总之", "综上所述", "因此之故", "由此可见");
    @Resource
    private PipelineProperties pipelineProperties;

    @Override
    public String getNodeType() {
        return "chunker";
    }

    @RagTraceNode(name = "分块", type = "上传管道", taskIdArg = "etlNode")
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        Document sourceDoc = context.getDocument();
        if (sourceDoc == null)
            return NodeResult.fail("未获取到文档");
        String text = sourceDoc.getText();
        if (!StringUtils.hasText(text))
            return NodeResult.fail("分块文本内容为空");

        JsonNode settings = config == null ? null : config.getSettings();

        int maxChunkSize = readInt(settings, "chunkSize", pipelineProperties.getTextChunkSize());
        int minMergeSize = readInt(settings, "minMergeSize", pipelineProperties.getMinChunkSizeChars());
        int maxNumChunks = readInt(settings, "maxNumChunks", pipelineProperties.getMaxNumChunks());

        ChunkStrategy strategy = determineStrategy(text, settings);
        log.info("使用分块策略: {}", strategy);

        List<Document> allChunks;
        switch (strategy) {
            case SMART -> {
                List<SemanticBlock> blocks = parseMarkdownToSemanticBlocks(text);
                allChunks = smartGreedyMerge(blocks, sourceDoc, maxChunkSize, minMergeSize);
            }
            case TITLE -> {
                int splitLevel = readInt(settings, "splitLevel", 2);
                allChunks = buildTitleChunks(text, sourceDoc, splitLevel, maxChunkSize);
                allChunks = enforceMinChunkSize(allChunks, minMergeSize, false);
            }
            case PARENT_CHILD -> {
                int parentChunkSize = readInt(settings, "parentChunkSize", 2048);
                int childChunkSize = readInt(settings, "childChunkSize", 512);
                int parentSplitLevel = readInt(settings, "parentSplitLevel", 2);
                allChunks = processParentChild(text, sourceDoc, parentChunkSize, childChunkSize,
                        parentSplitLevel, minMergeSize, maxNumChunks);
            }
            case REGEX -> {
                Pattern regex = getCustomRegex(settings);
                if (regex == null)
                    regex = Pattern.compile("\\n\\s*\\n");
                List<String> raw = splitByRegex(text, regex);
                allChunks = applyTokenSizeControl(raw, sourceDoc, maxChunkSize, minMergeSize, maxNumChunks);
            }
            default -> {
                List<String> paragraphs = Arrays.stream(text.split("\\n\\s*\\n"))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
                allChunks = applyTokenSizeControl(paragraphs, sourceDoc, maxChunkSize, minMergeSize, maxNumChunks);
            }
        }

        if (strategy == ChunkStrategy.TITLE || strategy == ChunkStrategy.PARENT_CHILD) {
            allChunks = enforceMinChunkSize(allChunks, minMergeSize, false);
        }

        Document updatedDoc = sourceDoc.mutate()
                .metadata("chunk_size", allChunks.size())
                .metadata("chunk_strategy", strategy.name())
                .build();
        context.setDocument(updatedDoc);
        allChunks = enrichChunkMetadata(allChunks, allChunks.size());
        context.setChunks(allChunks);
        skipChunk(context);
        log.info("分块完成，共 {} 个 chunk，策略={}", allChunks.size(), strategy);
        return NodeResult.ok("分块数量=" + allChunks.size());
    }

    // ==================== 策略决策 ====================
    private ChunkStrategy determineStrategy(String text, JsonNode settings) {
        if (settings != null && settings.has("strategy")) {
            ChunkStrategy user = ChunkStrategy.from(settings.get("strategy").asText());
            if (user != null)
                return user;
        }
        List<Heading> headings = extractHeadings(text);
        int headingCount = headings.size();
        int maxDepth = computeHeadingDepth(headings);

        if (settings != null && settings.has("regexPattern") && headingCount < 3) {
            return ChunkStrategy.REGEX;
        }
        if (headingCount >= 10 && maxDepth >= 3) {
            return ChunkStrategy.PARENT_CHILD;
        }
        if (headingCount >= 5 && maxDepth >= 2) {
            return ChunkStrategy.TITLE;
        }
        return ChunkStrategy.SMART;
    }

    // ==================== commonmark 辅助 ====================
    private List<Heading> extractHeadings(String markdown) {
        List<Heading> headings = new ArrayList<>();
        Node doc = MARKDOWN_PARSER.parse(markdown);
        for (Node node = doc.getFirstChild(); node != null; node = node.getNext()) {
            if (node instanceof Heading h)
                headings.add(h);
        }
        return headings;
    }

    private int computeHeadingDepth(List<Heading> headings) {
        if (headings.isEmpty())
            return 0;
        int maxDepth = 1;
        Deque<Integer> stack = new ArrayDeque<>();
        for (Heading h : headings) {
            int level = h.getLevel();
            while (!stack.isEmpty() && stack.peek() >= level)
                stack.pop();
            stack.push(level);
            maxDepth = Math.max(maxDepth, stack.size());
        }
        return maxDepth;
    }

    private String getLiteralContent(Node node) {
        if (node == null)
            return "";
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

    private String getRawText(Node node) {
        if (node == null)
            return "";
        StringBuilder sb = new StringBuilder();
        Node child = node.getFirstChild();
        while (child != null) {
            switch (child) {
                case Text t -> sb.append(t.getLiteral());
                case Code c -> sb.append('`').append(c.getLiteral()).append('`');
                case Emphasis emphasis -> sb.append('*').append(getRawText(child)).append('*');
                case StrongEmphasis strongEmphasis -> sb.append("**").append(getRawText(child)).append("**");
                case SoftLineBreak softLineBreak -> sb.append('\n');
                case HardLineBreak hardLineBreak -> sb.append('\n');
                default -> sb.append(getRawText(child));
            }
            child = child.getNext();
        }
        return sb.toString().trim();
    }

    private List<SemanticBlock> parseMarkdownToSemanticBlocks(String markdown) {
        List<SemanticBlock> blocks = new ArrayList<>();
        Node document = MARKDOWN_PARSER.parse(markdown);
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            SemanticBlock block = new SemanticBlock();
            block.nodeType = node.getClass();

            if (node instanceof Heading h) {
                block.headingLevel = h.getLevel();
                block.headingTitle = getLiteralContent(h);
                StringBuilder content = new StringBuilder(getRawText(node)).append('\n');
                Node next = node.getNext();
                while (next != null && !(next instanceof Heading hn && hn.getLevel() <= h.getLevel())) {
                    content.append(getRawText(next)).append("\n\n");
                    next = next.getNext();
                }
                block.content = content.toString().trim();
            } else if (node instanceof FencedCodeBlock f) {
                block.content = "```" + f.getInfo() + "\n" + f.getLiteral() + "```";
            } else if (node instanceof IndentedCodeBlock i) {
                block.content = i.getLiteral();
            } else if (node instanceof TableBlock || node instanceof BlockQuote || node instanceof Paragraph) {
                block.content = getRawText(node);
            } else if (node instanceof ThematicBreak) {
                continue;
            } else {
                block.content = getRawText(node);
            }

            if (!block.content.isEmpty()) {
                block.tokenCount = estimateTokens(block.content);
                blocks.add(block);
            }
        }
        if (blocks.isEmpty() && !markdown.isEmpty()) {
            SemanticBlock fallback = new SemanticBlock();
            fallback.nodeType = Paragraph.class;
            fallback.content = markdown;
            fallback.tokenCount = estimateTokens(markdown);
            blocks.add(fallback);
        }
        return blocks;
    }

    private List<Document> smartGreedyMerge(List<SemanticBlock> blocks, Document sourceDoc,
            int maxChunkSize, int minMergeSize) {
        List<Document> chunks = new ArrayList<>();
        List<SemanticBlock> buffer = new ArrayList<>();
        int bufferTokens = 0;

        for (SemanticBlock block : blocks) {
            if (block.nodeType == Heading.class && bufferTokens >= minMergeSize) {
                chunks.add(buildChunk(buffer, sourceDoc));
                buffer.clear();
                bufferTokens = 0;
            }
            int bt = block.tokenCount;
            if (buffer.isEmpty()) {
                buffer.add(block);
                bufferTokens = bt;
                continue;
            }
            if (bufferTokens + bt <= maxChunkSize) {
                buffer.add(block);
                bufferTokens += bt;
            } else {
                chunks.add(buildChunk(buffer, sourceDoc));
                buffer.clear();
                buffer.add(block);
                bufferTokens = bt;
            }
        }
        if (!buffer.isEmpty())
            chunks.add(buildChunk(buffer, sourceDoc));

        // 大块句子拆分
        List<Document> finalChunks = new ArrayList<>();
        for (Document chunk : chunks) {
            int tokens = estimateTokens(chunk.getText());
            int charLen = chunk.getText().length();
            // 当 token 估算或原始字符长度任一超过阈值时，强制拆分
            if (tokens > maxChunkSize || charLen > maxChunkSize * 3) {
                log.debug("触发大块拆分：token={}, 字符数={}, 阈值={}", tokens, charLen, maxChunkSize);
                finalChunks.addAll(splitLargeChunk(chunk, maxChunkSize));
            } else {
                finalChunks.add(chunk);
            }
        }
        return enforceMinChunkSize(finalChunks, minMergeSize, true);
    }

    private Document buildChunk(List<SemanticBlock> blocks, Document sourceDoc) {
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
        Map<String, Object> meta = new HashMap<>(sourceDoc.getMetadata());
        meta.put("section_title", title);
        meta.put("section_path", String.join(" > ", breadcrumbs));
        return new Document(content.toString().trim(), meta);
    }

    private List<Document> splitLargeChunk(Document chunk, int maxChunkSize) {
        String text = chunk.getText();
        List<String> sentences = splitIntoSentences(text);
        sentences = mergeLogicalSentences(sentences);
        List<String> groups = groupSentences(sentences, maxChunkSize);
        return groups.stream().map(g -> new Document(g, chunk.getMetadata())).toList();
    }

    // ==================== TITLE 策略 ====================
    private List<Document> buildTitleChunks(String markdown, Document sourceDoc, int splitLevel, int maxChunkSize) {
        List<Heading> all = extractHeadings(markdown);
        if (all.isEmpty()) {
            return splitByBlankLinesToChunks(markdown, sourceDoc, maxChunkSize);
        }
        // 使用所有标题作为切分边界（不再按 splitLevel 过滤，除非显式指定了较小的层级）
        List<Heading> boundaries = (splitLevel >= 6) ? all
                : all.stream().filter(h -> h.getLevel() <= splitLevel).toList();
        if (boundaries.isEmpty()) {
            return splitByBlankLinesToChunks(markdown, sourceDoc, maxChunkSize);
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
                    chunks.addAll(splitLongContent(prefix, "前言", sourceDoc, maxChunkSize));
                }
            }
            int end = (i + 1 < positions.size()) ? positions.get(i + 1) : markdown.length();
            String content = markdown.substring(pos, end).trim();
            String title = getLiteralContent(boundaries.get(i));
            chunks.addAll(splitLongContent(content, title, sourceDoc, maxChunkSize));
            start = end;
        }
        return chunks;
    }

    /**
     * 将长文本按最大字符数拆分成多个 Document，每个子块都保留相同的标题和面包屑。
     * 最大字符数估算：maxChunkSize * 3（1 token ≈ 3 字符）
     */
    private List<Document> splitLongContent(String content, String title, Document sourceDoc, int maxChunkSize) {
        int maxChars = maxChunkSize * 3;
        if (content.length() <= maxChars) {
            return List.of(createTitleChunk(title, content, sourceDoc));
        }
        List<Document> subDocs = new ArrayList<>();
        String[] paragraphs = content.split("\\n\\s*\\n");
        StringBuilder buffer = new StringBuilder();
        int currentLen = 0;
        for (String para : paragraphs) {
            int paraLen = para.length();
            if (currentLen + paraLen > maxChars && !buffer.isEmpty()) {
                // 如果缓冲区的最后一段看起来像一个标题，则强制把当前段落也加进去（允许稍微超出长度限制）
                // 防止标题孤悬在上一个 chunk 末尾
                String last = lastParagraph(buffer.toString());
                if (!isHeadingLike(last)) {
                    subDocs.add(createTitleChunk(title, buffer.toString().trim(), sourceDoc));
                    buffer.setLength(0);
                    currentLen = 0;
                }
                // 否则（是标题）不清空 buffer，继续追加当前段落
            }
            if (!buffer.isEmpty()) {
                buffer.append("\n\n");
                currentLen += 2;
            }
            buffer.append(para);
            currentLen += paraLen;
        }
        if (!buffer.isEmpty()) {
            subDocs.add(createTitleChunk(title, buffer.toString().trim(), sourceDoc));
        }
        return subDocs;
    }

    // -------------------- 新增辅助方法 --------------------

    /**
     * 获取文本中最后一个段落（由空行分隔）
     */
    private String lastParagraph(String text) {
        if (text == null || text.isEmpty())
            return "";
        String[] parts = text.split("\\n\\s*\\n");
        return parts[parts.length - 1].trim();
    }

    /**
     * 判断一行文本是否像是一个标题。
     * 支持：Markdown # 标题、中文数字序号（如“二、资源下载”）、数字序号、第X章/节等。
     */
    private boolean isHeadingLike(String line) {
        if (line == null || line.isEmpty())
            return false;
        // Markdown 标题
        if (line.startsWith("#"))
            return true;
        // 中文数字标题，如“一、”“二.”“三、资源下载”
        if (line.matches("^[一二三四五六七八九十]+[、.]\\s*.*"))
            return true;
        // 普通数字序号标题，如“1. 介绍”
        if (line.matches("^\\d+[、.]\\s+.*"))
            return true;
        // 第X章 / 第X节
        return line.matches("^第[一二三四五六七八九十\\d]+[章节].*");
    }

    private List<Document> splitByBlankLinesToChunks(String text, Document sourceDoc, int maxChunkSize) {
        return Arrays.stream(text.split("\\n\\s*\\n"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .flatMap(part -> splitLongContent(part, "前言", sourceDoc, maxChunkSize).stream())
                .toList();
    }

    private Document createTitleChunk(String title, String content, Document sourceDoc) {
        Map<String, Object> meta = new HashMap<>(sourceDoc.getMetadata());
        meta.put("section_title", title);
        meta.put("section_path", title);
        return new Document(content, meta);
    }

    // ==================== PARENT-CHILD 策略 ====================
    private List<Document> processParentChild(String markdown, Document sourceDoc,
            int parentChunkSize, int childChunkSize,
            int parentSplitLevel, int minMergeSize,
            int maxNumChunks) {
        List<Document> parents = generateParentChunks(markdown, sourceDoc, parentChunkSize, parentSplitLevel,
                maxNumChunks);
        List<Document> children = new ArrayList<>();
        int remaining = maxNumChunks > 0 ? maxNumChunks : Integer.MAX_VALUE;
        for (Document parent : parents) {
            if (remaining <= 0)
                break;
            List<SemanticBlock> blocks = parseMarkdownToSemanticBlocks(parent.getText());
            List<Document> sub = smartGreedyMerge(blocks, sourceDoc, childChunkSize, minMergeSize);
            for (Document s : sub) {
                if (remaining <= 0)
                    break;
                Map<String, Object> meta = new HashMap<>(s.getMetadata());
                meta.put("parent_chunk_id", parent.getId());
                meta.put("parent_title", parent.getMetadata().getOrDefault("section_title", ""));
                meta.put("chunk_type", "child");
                children.add(new Document(Objects.requireNonNull(s.getText(), "子块文本为空"), meta));
                remaining--;
            }
        }
        return children;
    }

    private List<Document> generateParentChunks(String markdown, Document sourceDoc,
            int parentChunkSize, int parentSplitLevel,
            int maxNumChunks) {
        List<Heading> all = extractHeadings(markdown);
        List<Heading> boundaries = all.stream().filter(h -> h.getLevel() <= parentSplitLevel).toList();

        if (boundaries.isEmpty()) {
            if (estimateTokens(markdown) <= parentChunkSize) {
                return List.of(createParentChunk("全文", markdown, sourceDoc));
            } else {
                return splitLargeIntoParentChunks(markdown, parentChunkSize, sourceDoc, "全文");
            }
        }

        List<Integer> positions = boundaries.stream()
                .map(h -> getStartOffset(h, markdown))
                .sorted()
                .toList();
        List<Document> parents = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            if (maxNumChunks > 0 && parents.size() >= maxNumChunks)
                break;
            int pos = positions.get(i);
            int end = (i + 1 < positions.size()) ? positions.get(i + 1) : markdown.length();
            String content = markdown.substring(pos, end).trim();
            String title = getLiteralContent(boundaries.get(i));
            if (estimateTokens(content) <= parentChunkSize) {
                parents.add(createParentChunk(title, content, sourceDoc));
            } else {
                parents.addAll(splitLargeIntoParentChunks(content, parentChunkSize, sourceDoc, title));
            }
        }
        return parents;
    }

    private Document createParentChunk(String title, String content, Document sourceDoc) {
        Map<String, Object> meta = new HashMap<>(sourceDoc.getMetadata());
        meta.put("section_title", title);
        meta.put("chunk_type", "parent");
        return Document.builder()
                .id(UUID.randomUUID().toString())
                .text(content)
                .metadata(meta)
                .build();
    }

    private List<Document> splitLargeIntoParentChunks(String content, int maxTokens, Document sourceDoc, String title) {
        List<Document> chunks = new ArrayList<>();
        String[] paragraphs = content.split("\\n\\s*\\n");
        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;
        for (String para : paragraphs) {
            int pt = estimateTokens(para);
            if (bufferTokens + pt > maxTokens && !buffer.isEmpty()) {
                chunks.add(createParentChunk(title, buffer.toString().trim(), sourceDoc));
                buffer.setLength(0);
                bufferTokens = 0;
            }
            if (!buffer.isEmpty())
                buffer.append("\n\n");
            buffer.append(para);
            bufferTokens += pt;
        }
        if (!buffer.isEmpty())
            chunks.add(createParentChunk(title, buffer.toString().trim(), sourceDoc));
        return chunks;
    }

    // ==================== REGEX 策略 ====================
    private List<String> splitByRegex(String text, Pattern regex) {
        return Arrays.stream(regex.split(text)).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private List<Document> applyTokenSizeControl(List<String> raw, Document sourceDoc,
            int maxChunkSize, int minMergeSize,
            int maxNumChunks) {
        List<Document> result = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        int bufferTokens = 0;

        for (String chunk : raw) {
            if (maxNumChunks > 0 && result.size() >= maxNumChunks)
                break;
            int ct = estimateTokens(chunk);
            if (ct < minMergeSize) {
                if (buffer.isEmpty()) {
                    buffer.append(chunk);
                    bufferTokens = ct;
                } else if (bufferTokens + ct <= maxChunkSize) {
                    buffer.append("\n\n").append(chunk);
                    bufferTokens += ct;
                } else {
                    result.add(new Document(buffer.toString().trim(), sourceDoc.getMetadata()));
                    buffer.setLength(0);
                    buffer.append(chunk);
                    bufferTokens = ct;
                }
                continue;
            }
            if (!buffer.isEmpty()) {
                result.add(new Document(buffer.toString().trim(), sourceDoc.getMetadata()));
                buffer.setLength(0);
                bufferTokens = 0;
            }
            if (ct <= maxChunkSize) {
                result.add(new Document(chunk, sourceDoc.getMetadata()));
            } else {
                List<String> sub = splitLargeParagraph(chunk, maxChunkSize);
                sub.forEach(s -> result.add(new Document(s, sourceDoc.getMetadata())));
            }
        }
        if (!buffer.isEmpty())
            result.add(new Document(buffer.toString().trim(), sourceDoc.getMetadata()));
        return enforceMinChunkSize(result, minMergeSize, true);
    }

    // ==================== 句子切分与合并 ====================
    private List<String> splitIntoSentences(String text) {
        if (text == null || text.isEmpty())
            return List.of();
        return Arrays.stream(SENTENCE_BOUNDARY.split(text)).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private List<String> mergeLogicalSentences(List<String> sentences) {
        if (sentences.size() <= 1)
            return sentences;
        List<String> merged = new ArrayList<>();
        StringBuilder current = new StringBuilder(sentences.getFirst());
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

    /**
     * 通过 SourceSpan 的行列信息，结合原始文本，计算节点在整个文本中的起始字符索引。
     * 若无法获取则返回 0。
     */
    // ==================== 2. 带文本回退的偏移量获取 ====================

    /**
     * 通过 SourceSpan 的行列信息，结合原始文本，计算节点在全文中的起始字符索引。
     * 若 SourceSpan 不可用，则通过搜索标题文本内容进行回退定位。
     */
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
                if (fullText.charAt(i) == '\n') {
                    currentLine++;
                }
            }
            return lineStart + columnIndex;
        }
        String headingContent = getLiteralContent(node);
        if (!headingContent.isEmpty()) {
            int idx = fullText.indexOf(headingContent);
            if (idx >= 0) {
                log.debug("通过内容匹配定位标题 {} 偏移: {}", headingContent, idx);
                return idx;
            }
        }

        log.warn("无法获取节点 {} 的 SourceSpan，且内容匹配失败，使用偏移量 0", node.getClass().getSimpleName());
        return 0;
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
                int st = estimateTokens(sent);
                if (curTokens == 0 || curTokens + st <= maxTokens) {
                    if (curTokens > 0) {
                        sb.append(' ');
                        curTokens++;
                    }
                    sb.append(sent);
                    curTokens += st;
                    i++;
                } else
                    break;
            }
            if (!sb.isEmpty())
                chunks.add(sb.toString().trim());
        }
        return chunks;
    }

    private List<String> splitLargeParagraph(String paragraph, int maxChunkSize) {
        List<String> sentences = splitIntoSentences(paragraph);
        sentences = mergeLogicalSentences(sentences);
        return groupSentences(sentences, maxChunkSize);
    }

    // ==================== 最小块强制合并 ====================
    private List<Document> enforceMinChunkSize(List<Document> chunks, int minMergeSize, boolean allowCrossSection) {
        if (chunks == null || chunks.isEmpty())
            return new ArrayList<>();
        if (chunks.size() == 1)
            return chunks;
        List<Document> result = new ArrayList<>();
        Document current = chunks.getFirst();
        for (int i = 1; i < chunks.size(); i++) {
            Document next = chunks.get(i);
            boolean canMerge = current.getText().length() < minMergeSize
                    && (allowCrossSection || isSameSection(current, next));
            if (canMerge) {
                current = new Document(current.getText() + "\n" + next.getText(), current.getMetadata());
            } else {
                result.add(current);
                current = next;
            }
        }
        if (current.getText().length() < minMergeSize && !result.isEmpty()) {
            Document last = result.removeLast();
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
        if (pa == null || pb == null)
            return true;
        return pa.equals(pb) || pa.startsWith(pb + " >") || pb.startsWith(pa + " >");
    }

    // ==================== Token 估算 ====================
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty())
            return 0;
        int ch = 0, other = 0;
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)
                ch++;
            else if (!Character.isWhitespace(c))
                other++;
        }
        return (int) Math.ceil(ch / 1.5 + other / 4.0);
    }

    // ==================== 元数据富化 ====================
    private List<Document> enrichChunkMetadata(List<Document> chunks, int total) {
        if (chunks == null || chunks.isEmpty())
            return List.of();
        List<Document> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document c = chunks.get(i);
            if (c == null || !StringUtils.hasText(c.getText()))
                continue;
            Map<String, Object> meta = new HashMap<>(c.getMetadata());
            meta.put("chunkId", i + 1);
            meta.put("chunkSize", total);
            String fileId = (String) meta.getOrDefault("fileId", "default");
            result.add(Document.builder()
                    .id(fileId + ":" + String.format("%06d", i + 1))
                    .text(c.getText())
                    .metadata(meta)
                    .build());
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
                        if (item == null)
                            continue;
                        if (item instanceof Number n) {
                            keepChunkList.add(n.intValue());
                        }
                    }
                }
                if (!keepChunkList.isEmpty()) {
                    List<Document> original = context.getChunks();
                    List<Document> filtered = new ArrayList<>();
                    for (Document chunk : original) {
                        int chunkId = Integer.parseInt(chunk.getId().split(":")[1]);
                        if (keepChunkList.contains(chunkId)) {
                            filtered.add(chunk);
                        }
                    }
                    Document docWithCopyMeta = context.getDocument().mutate()
                            .metadata(IngestionContext.META_CHUNK_SIZE, filtered.size())
                            .build();
                    context.setDocument(docWithCopyMeta);
                    context.setChunks(filtered);
                    context.setChunks(filtered);
                    log.info("分块跳过: 原始分块数={}，保留分块数={}", original.size(), filtered.size());
                }
            }
        } catch (Exception e) {
            // 不影响主流程，只记录调试日志
            log.debug("解析 META_COPY_CHUNK 或过滤分块时发生异常", e);
        }
    }

    // ==================== 配置读取 ====================
    private int readInt(JsonNode s, String key, int def) {
        if (s != null && s.has(key) && s.get(key).canConvertToInt())
            return s.get(key).asInt();
        return def;
    }

    private Pattern getCustomRegex(JsonNode settings) {
        if (settings == null || !settings.has("regexPattern"))
            return null;
        String regex = settings.get("regexPattern").asText();
        if (regex.isEmpty())
            return null;
        return Pattern.compile(regex, Pattern.MULTILINE);
    }

    public enum ChunkStrategy {
        SMART,
        PARENT_CHILD,
        TITLE,
        REGEX;

        public static ChunkStrategy from(String name) {
            if (name == null || name.isBlank())
                return null;
            try {
                return valueOf(name.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("未知分块策略: {}，将使用自动选择", name);
                return null;
            }
        }
    }

    // ==================== 语义块与 SMART 分块 ====================
    private static class SemanticBlock {
        Class<? extends Node> nodeType;
        String content = "";
        int headingLevel = 0;
        String headingTitle = "";
        int tokenCount = 0;
    }
}