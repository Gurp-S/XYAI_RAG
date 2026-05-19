package com.XYai.myai.rag.etlpipeline.nodes;

import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.etlpipeline.pojo.*;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档分块节点（ETL 流程 chunker 环节）
 * 策略：递归子母分块 —— 按标题层级构建章节树，再智能切分，保证主题纯净，大小适配。
 */
@Slf4j
@Component
public class Chunker implements Ingestion {

    // 多种标题模式，按优先级排列
    private static final Pattern[] HEADING_PATTERNS = {
            Pattern.compile("^(#{1,6}\\s)(.*)", Pattern.MULTILINE),               // Markdown H1~H6
            Pattern.compile("^(第[一二三四五六七八九十百千]+[章节]\\s*.*)", Pattern.MULTILINE), // 中文章节
            Pattern.compile("^(\\d+(\\.\\d+)*\\.?\\s+.+)", Pattern.MULTILINE),    // 数字编号
            Pattern.compile("^(（[一二三四五六七八九十]+）\\s*.+)", Pattern.MULTILINE)      // 中文括号编号
    };

    // 句子边界正则：中英文句末标点
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("(?<=[。！？；.!?])\\s*");

    @Resource
    private PipelineProperties pipelineProperties;

    @Override
    public String getNodeType() {
        return "chunker";
    }

    @RagTraceNode(name = "分块", type = "上传管道")
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        Document sourceDoc = context.getDocument();
        if (sourceDoc == null) {
            return NodeResult.fail("未获取到文档");
        }

        String text = sourceDoc.getText();
        if (!StringUtils.hasText(text)) {
            return NodeResult.fail("分块文本内容为空");
        }

        JsonNode settings = config == null ? null : config.getSettings();
        int maxChunkSize = readInt(settings, "chunkSize", pipelineProperties.getTextChunkSize());
        int overlapSize = readInt(settings, "overlapSize", pipelineProperties.getDefaultOverlapSize());
        int minMergeSize = readInt(settings, "minMergeSize", pipelineProperties.getMinChunkSizeChars());
        int maxNumChunks = readInt(settings, "maxNumChunks", pipelineProperties.getMaxNumChunks());

        // 1. 递归解析文档章节结构
        List<Section> sections = parseSections(text);

        List<Document> allChunks;
        if (sections.isEmpty()) {
            allChunks = splitBySentenceWindow(sourceDoc, text, maxChunkSize, overlapSize);
        } else {
            // 2. 按章节树扁平化并自适应切分
            allChunks = flattenSections(sections, sourceDoc, maxChunkSize, minMergeSize, overlapSize, maxNumChunks);
        }

        // 更新元数据
        Document updatedDoc = sourceDoc.mutate()
                .metadata("chunk_size", allChunks.size())
                .build();
        context.setDocument(updatedDoc);

        // 3. 补齐入chunkId, chunkSize
        allChunks = enrichChunkMetadata(allChunks, allChunks.size());
        context.setChunks(allChunks);

        // 4. 处理已存在的分块筛选逻辑
        skipChunk(context);

        log.info("递归分块完成，共 {} 个 chunk", context.getChunks().size());
        return NodeResult.ok("分块数量=" + context.getChunks().size());
    }

    // ===================== 章节解析（支持多种标题、处理开头无标题内容） =====================

    /**
     * 使用多种标题模式解析章节树，处理文档开头无标题的内容
     */
    private List<Section> parseSections(String text) {
        List<HeadingMatch> matches = new ArrayList<>();
        Set<Integer> usedStarts = new HashSet<>();

        // 按优先级遍历所有标题模式，收集匹配
        for (Pattern pattern : HEADING_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                int start = matcher.start();
                if (!usedStarts.contains(start)) {
                    usedStarts.add(start);
                    String title = matcher.group().trim();
                    int level = calculateLevel(title, pattern);
                    matches.add(new HeadingMatch(start, matcher.end(), title, level));
                }
            }
        }

        // 按起始位置排序
        matches.sort(Comparator.comparingInt(m -> m.

                start));

        // 构建章节树
        List<Section> roots = new ArrayList<>();
        Deque<Section> stack = new ArrayDeque<>();
        Map<Section, StringBuilder> contentBuilders = new IdentityHashMap<>();
        int lastEnd = 0;

        // 如果第一个标题之前有内容，创建“前言”章节
        if (!matches.isEmpty()) {
            HeadingMatch firstMatch = matches.getFirst();
            if (firstMatch.start > 0) {
                String prefaceText = text.substring(0, firstMatch.start).trim();
                if (!prefaceText.isEmpty()) {
                    Section preface = new Section();
                    preface.setTitle("前言");
                    preface.setLevel(0);
                    preface.setChildren(new ArrayList<>());
                    preface.setBreadcrumbs(new ArrayList<>());
                    preface.setContent(prefaceText);
                    roots.add(preface);
                    // 前言不参与后续标题处理，但剩余部分从 firstMatch.start 开始
                    lastEnd = firstMatch.getStart();
                }
            }
        }

        // 遍历标题匹配，划分段落
        for (HeadingMatch match : matches) {
            // 两个标题之间的文本属于上一个标题
            if (lastEnd < match.start) {
                String segment = text.substring(lastEnd, match.start).trim();
                if (!segment.isEmpty() && !stack.isEmpty()) {
                    Section currentSection = stack.peek();
                    contentBuilders.computeIfAbsent(currentSection, k -> new StringBuilder())
                            .append(segment).append("\n");
                }
            }

            // 创建新 Section
            Section newSection = new Section();
            newSection.setTitle(match.
                    title);
            newSection.setLevel(match.level);
            newSection.setChildren(new ArrayList<>());
            newSection.setBreadcrumbs(new ArrayList<>());

            // 根据层级确定父节点
            while (!stack.isEmpty() && stack.peek().getLevel() >= match.
                    level) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                roots.add(newSection);
            } else {
                Section parent = stack.peek();
                parent.getChildren().add(newSection);
                newSection.getBreadcrumbs().addAll(parent.getBreadcrumbs());
            }
            newSection.getBreadcrumbs().add(match.title);
            stack.push(newSection);

            lastEnd = match.end;
        }

        // 处理最后一个标题之后的剩余文本
        if (lastEnd < text.length()) {
            String remaining = text.substring(lastEnd).trim();
            if (!remaining.isEmpty() && !stack.isEmpty()) {
                Section lastSection = stack.peek();
                contentBuilders.computeIfAbsent(lastSection, k -> new StringBuilder())
                        .append(remaining).append("\n");
            }
        }

        // 将累积的内容设置到对应的 Section 上
        contentBuilders.forEach((section, builder) -> {
            String fullContent = builder.toString().trim();
            if (!fullContent.isEmpty()) {
                section.setContent(fullContent);
            }
        });

        return roots;
    }

    /**
     * 根据匹配的标题和模式计算层级
     */
    private int calculateLevel(String title, Pattern pattern) {
        if (pattern == HEADING_PATTERNS[0]) { // Markdown
            return determineMarkdownLevel(title);
        } else if (pattern == HEADING_PATTERNS[2]) { // 数字编号
            return determineNumberedLevel(title);
        } else {
            return 1; // 其他模式默认为一级标题
        }
    }

    private int determineMarkdownLevel(String headingLine) {
        if (headingLine.startsWith("#")) {
            int count = 0;
            for (char c : headingLine.toCharArray()) {
                if (c == '#') count++;
                else break;
            }
            return Math.max(1, count);
        }
        return 1;
    }

    private int determineNumberedLevel(String headingLine) {
        // 计算点的个数，如 "1." 层级1，"1.1" 层级2
        int dots = 0;
        for (char c : headingLine.toCharArray()) {
            if (c == '.') dots++;
            else if (!Character.isDigit(c)) break;
        }
        return dots + 1;
    }

    /**
     * 扁平化章节树，并对小章节进行合并，大章节按句子边界切分
     */
    private List<Document> flattenSections(List<Section> sections, Document sourceDoc,
                                           int maxChunkSize, int minMergeSize, int overlapSize, int maxNumChunks) {
        List<Document> chunks = new ArrayList<>();

        // 第一步：递归处理所有子章节，得到扁平列表（保持顺序，但先处理子节点可能会导致顺序错乱，我们采用深度优先）
        // 此处我们使用显式的扁平化：遍历每个 section，先将其本身作为一个单元处理，然后递归子节点追加在后面
        // 为了支持兄弟章节合并，我们需要在同一层级进行合并。
        // 将当前层级的 sections 先处理（可能会合并），然后再处理子节点。
        List<Section> processed = mergeSmallSections(sections, maxChunkSize, minMergeSize);

        for (Section sec : processed) {
            // 处理该章节的内容（如果存在）
            if (sec.getContent() != null && !sec.getContent().isEmpty()) {
                if (sec.getContent().length() <= maxChunkSize) {
                    chunks.add(createChunkFromSection(sec, sec.getContent(), sourceDoc));
                } else {
                    // 按句子切分，保证句子完整
                    List<String> sentences = splitIntoSentences(sec.getContent());
                    List<String> chunkTexts = groupSentences(sentences, maxChunkSize,
                            overlapSize > 0 ? 1 : 0);
                    for (String chunkText : chunkTexts) {
                        Document chunk = createChunkFromSection(sec, chunkText, sourceDoc);
                        chunks.add(chunk);
                    }
                }
            }
            // 递归处理子章节（子章节保持原有层级关系，不再参与平级合并）
            if (!sec.getChildren().isEmpty()) {
                chunks.addAll(flattenSections(sec.getChildren(), sourceDoc, maxChunkSize, minMergeSize, overlapSize, maxNumChunks));
            }
        }
        return chunks;
    }

    // ===================== 章节扁平化、合并、句子切分 =====================

    /**
     * 合并同级小章节：将内容长度小于 minMergeSize 的相邻叶子章节合并，形成更大的块
     * 注意：只合并没有子章节的叶子节点，避免破坏层级结构
     */
    private List<Section> mergeSmallSections(List<Section> sections, int maxChunkSize, int minMergeSize) {
        List<Section> result = new ArrayList<>();
        List<Section> buffer = new ArrayList<>();
        int bufferLen = 0;

        for (Section sec : sections) {
            boolean isLeaf = sec.getChildren().isEmpty();
            // 只有叶子章节且内容非空才参与合并
            if (isLeaf && sec.getContent() != null && !sec.getContent().isEmpty()) {
                int len = sec.getContent().length();
                if (len < minMergeSize) {
                    // 尝试加入缓冲区
                    if (bufferLen + len <= maxChunkSize) {
                        buffer.add(sec);
                        bufferLen += len;
                    } else {
                        // 缓冲区已满，先合并输出缓冲区，再将当前章节放入新缓冲区
                        if (!buffer.isEmpty()) {
                            result.add(createMergedSection(buffer));
                            buffer.clear();
                            bufferLen = 0;
                        }
                        buffer.add(sec);
                        bufferLen = len;
                    }
                } else {
                    // 当前章节内容较大，先输出缓冲区，再单独输出这个章节
                    if (!buffer.isEmpty()) {
                        result.add(createMergedSection(buffer));
                        buffer.clear();
                        bufferLen = 0;
                    }
                    result.add(sec);
                }
            } else {
                // 非叶子节点或有特殊情况（无内容）直接输出，并先输出缓冲区
                if (!buffer.isEmpty()) {
                    result.add(createMergedSection(buffer));
                    buffer.clear();
                    bufferLen = 0;
                }
                result.add(sec);
            }
        }
        // 处理剩余的缓冲区
        if (!buffer.isEmpty()) {
            result.add(createMergedSection(buffer));
        }
        return result;
    }

    /**
     * 将多个小章节合并为一个虚拟章节，内容拼接标题和正文
     */
    private Section createMergedSection(List<Section> sections) {
        if (sections.isEmpty()) return null;
        if (sections.size() == 1) return sections.getFirst();

        Section first = sections.getFirst();
        Section merged = new Section();
        merged.setTitle(first.getTitle());
        merged.setLevel(first.getLevel());
        merged.setBreadcrumbs(new ArrayList<>(first.getBreadcrumbs()));
        merged.setChildren(new ArrayList<>());

        StringBuilder sb = new StringBuilder();
        for (Section sec : sections) {
            sb.append(sec.getTitle()).append("\n");
            sb.append(sec.getContent()).append("\n");
        }
        merged.setContent(sb.toString().trim());
        return merged;
    }

    /**
     * 将文本按句子边界分割，返回句子列表（标点保留在句子末尾）
     */
    private List<String> splitIntoSentences(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        String[] parts = SENTENCE_BOUNDARY.split(text);
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                sentences.add(trimmed);
            }
        }
        return sentences;
    }

    // ===================== 句子处理工具 =====================

    /**
     * 将句子列表按目标大小分组，返回多个文本块，允许句子重叠
     *
     * @param sentences    句子列表
     * @param maxSize      每块最大字符数
     * @param overlapCount 相邻块之间重叠的句子数
     */
    private List<String> groupSentences(List<String> sentences, int maxSize, int overlapCount) {
        List<String> chunks = new ArrayList<>();
        int i = 0;
        while (i < sentences.size()) {
            StringBuilder sb = new StringBuilder();
            int currentLen = 0;
            int j = i;
            while (j < sentences.size()) {
                String sent = sentences.get(j);
                int sentLen = sent.length();
                if (currentLen + sentLen <= maxSize || currentLen == 0) {
                    sb.append(sent);
                    currentLen += sentLen;
                    j++;
                } else {
                    break;
                }
            }
            if (!sb.isEmpty()) {
                chunks.add(sb.toString().trim());
            }
            // 计算下一个起始位置，考虑重叠
            int nextStart = Math.max(i + 1, j - overlapCount);
            if (nextStart <= i) {
                i++; // 防止死循环
            } else {
                i = nextStart;
            }
        }
        return chunks;
    }

    // ===================== 分块方法 =====================

    /**
     * 基于句子的滑动窗口降级方案，确保语义完整性
     */
    private List<Document> splitBySentenceWindow(Document sourceDoc, String text, int chunkSize, int overlapSize) {
        List<Document> chunks = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return chunks;
        }

        List<String> sentences = splitIntoSentences(text);
        if (sentences.isEmpty()) {
            // 无句子则退化为字符滑动窗口
            return splitByCharWindow(sourceDoc, text, chunkSize, overlapSize);
        }

        // 将句子按 chunkSize 分组，重叠若干句子
        int overlapSentences = overlapSize > 0 ? 1 : 0; // 简单起见，重叠1个句子
        List<String> groupedTexts = groupSentences(sentences, chunkSize, overlapSentences);

        Map<String, Object> baseMeta = sourceDoc.getMetadata();
        for (String groupedText : groupedTexts) {
            Map<String, Object> meta = new HashMap<>(baseMeta);
            chunks.add(new Document(groupedText, meta));
        }
        return chunks;
    }

    /**
     * 字符级滑动窗口，作为最终降级方案
     */
    private List<Document> splitByCharWindow(Document sourceDoc, String text, int chunkSize, int overlapSize) {
        List<Document> chunks = new ArrayList<>();
        Map<String, Object> baseMeta = sourceDoc.getMetadata();
        int start = 0;
        int textLength = text.length();
        int step = Math.max(1, chunkSize - overlapSize);

        while (start < textLength) {
            int end = Math.min(start + chunkSize, textLength);
            String chunkText = text.substring(start, end).trim();
            if (!chunkText.isEmpty()) {
                Map<String, Object> meta = new HashMap<>(baseMeta);
                chunks.add(new Document(chunkText, meta));
            }
            if (end >= textLength) break;
            start += step;
        }
        return chunks;
    }

    private Document createChunkFromSection(Section section, String text, Document sourceDoc) {
        Map<String, Object> metadata = new HashMap<>(sourceDoc.getMetadata());
        metadata.put("section_title", section.getTitle());
        metadata.put("section_path", String.join(" > ", section.getBreadcrumbs()));
        return new Document(text, metadata);
    }

    // ===================== 元数据操作 =====================

    private List<Document> enrichChunkMetadata(List<Document> chunks, int totalChunkCount) {
        if (chunks == null || chunks.isEmpty()) return List.of();

        List<Document> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            if (chunk == null || !StringUtils.hasText(chunk.getText())) continue;

            HashMap<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            int chunkId = i + 1;
            metadata.put("chunkId", chunkId);
            metadata.put("chunkSize", totalChunkCount);

            String fileId = (String) metadata.getOrDefault("fileId", "default");
            String expectedId = fileId + ":" + String.format("%06d", chunkId);

            result.add(Document.builder()
                    .id(expectedId)
                    .text(chunk.getText())
                    .metadata(metadata)
                    .build());
        }
        return result;
    }

    /**
     * 根据 META_SKIP_CHUNK 元数据过滤已经有的 chunk
     */
    private void skipChunk(IngestionContext context) {
        try {
            Object chunkCopy = context.getDocument().getMetadata().get(IngestionContext.META_SKIP_CHUNK);
            if (chunkCopy == null) return;

            // 安全获取文档
            Document doc = context.getDocument();
            if (doc == null) {
                log.warn("skipChunkCopy 时上下文文档为空，跳过过滤");
                return;
            }

            // 规范化为 List<Long>
            List<Long> keepList = new ArrayList<>();
            if (chunkCopy instanceof Collection<?> col) {
                for (Object item : col) {
                    if (item == null) continue;
                    if (item instanceof Number n) {
                        keepList.add(n.longValue());
                    }
                }
            }
            if (!keepList.isEmpty()) {
                List<Document> original = context.getChunks();
                if (original == null) {
                    return;
                }
                // 获取chunkId写入
                context.setChunks(original.stream().filter(
                        d -> {
                            String s = d.getId().split(":")[1];
                            long cid = Long.parseLong(s);
                            return keepList.contains(cid);
                        }
                ).toList());
            }
        } catch (Exception e) {
            log.warn("解析 META_SKIP_CHUNK 或过滤分块时发生异常", e);
        }
    }

    // 工具方法：从 JsonNode settings 中读取 int 值，不存在则返回默认值
    private int readInt(JsonNode settings, String key, int defaultValue) {
        if (settings != null && settings.has(key) && settings.get(key).canConvertToInt()) {
            return settings.get(key).asInt();
        }
        return defaultValue;
    }
}