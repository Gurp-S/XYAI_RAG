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

@Slf4j
@Component
public class Chunker implements Ingestion {

    // 只保留真正的章节标题模式，禁止列表项被误判
    private static final Pattern[] HEADING_PATTERNS = {
            Pattern.compile("^#{1,6}\\s+.*", Pattern.MULTILINE),
            Pattern.compile("^第[一二三四五六七八九十\\d]+[章节]\\s*.{3,}", Pattern.MULTILINE),
            Pattern.compile("^[一二三四五六七八九十]+[、，.]\\s*.{10,}", Pattern.MULTILINE),
            Pattern.compile("^（[一二三四五六七八九十]+）\\s*.{10,}", Pattern.MULTILINE)
    };

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile(
            "(?<=[。！？.!?])\\s+|(?<=[.!?])(?=[A-Z\\n])"
    );

    @Resource
    private PipelineProperties pipelineProperties;

    @Override
    public String getNodeType() {
        return "chunker";
    }

    @RagTraceNode(name = "分块", type = "上传管道")
    public NodeResult execute(IngestionContext context, NodeConfig config) {
        Document sourceDoc = context.getDocument();
        if (sourceDoc == null) return NodeResult.fail("未获取到文档");
        String text = sourceDoc.getText();
        if (!StringUtils.hasText(text)) return NodeResult.fail("分块文本内容为空");

        JsonNode settings = config == null ? null : config.getSettings();
        int maxChunkSize = readInt(settings, "chunkSize", pipelineProperties.getTextChunkSize());
        int overlapSize = 0;
        int minMergeSize = Math.max(
                readInt(settings, "minMergeSize", pipelineProperties.getMinChunkSizeChars()),
                512
        );
        int maxNumChunks = readInt(settings, "maxNumChunks", pipelineProperties.getMaxNumChunks());

        // 1. 章节解析
        List<Section> sections = parseSections(text);
        if (sections.isEmpty()) {
            // 无标题时按空行分段，保留段落边界
            sections = splitByBlankLines(text);
        }

        // 2. 扁平化切分（所有文本走同一通道）
        List<Document> allChunks = flattenSections(sections, sourceDoc, maxChunkSize, minMergeSize, overlapSize, maxNumChunks);

        // 3. 强制合并短块（确保所有块 >= minMergeSize）
        allChunks = enforceMinChunkSize(allChunks, minMergeSize, sourceDoc);

        // 更新元数据
        Document updatedDoc = sourceDoc.mutate().metadata("chunk_size", allChunks.size()).build();
        context.setDocument(updatedDoc);
        allChunks = enrichChunkMetadata(allChunks, allChunks.size());
        context.setChunks(allChunks);
        skipChunk(context);

        log.info("分块完成，共 {} 个 chunk", context.getChunks().size());
        return NodeResult.ok("分块数量=" + context.getChunks().size());
    }

    // ===================== 章节解析 =====================

    private List<Section> parseSections(String text) {
        List<HeadingMatch> matches = new ArrayList<>();
        Set<Integer> usedStarts = new HashSet<>();

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

        matches.sort(Comparator.comparingInt(m -> m.start));

        List<Section> roots = new ArrayList<>();
        Deque<Section> stack = new ArrayDeque<>();
        Map<Section, StringBuilder> contentBuilders = new IdentityHashMap<>();
        int lastEnd = 0;

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
                    lastEnd = firstMatch.getStart();
                }
            }
        }

        for (HeadingMatch match : matches) {
            if (lastEnd < match.start) {
                String segment = text.substring(lastEnd, match.start).trim();
                if (!segment.isEmpty() && !stack.isEmpty()) {
                    Section currentSection = stack.peek();
                    contentBuilders.computeIfAbsent(currentSection, k -> new StringBuilder())
                            .append(segment).append("\n");
                }
            }

            Section newSection = new Section();
            newSection.setTitle(match.title);
            newSection.setLevel(match.level);
            newSection.setChildren(new ArrayList<>());
            newSection.setBreadcrumbs(new ArrayList<>());

            while (!stack.isEmpty() && stack.peek().getLevel() >= match.level) {
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

        if (lastEnd < text.length()) {
            String remaining = text.substring(lastEnd).trim();
            if (!remaining.isEmpty() && !stack.isEmpty()) {
                Section lastSection = stack.peek();
                contentBuilders.computeIfAbsent(lastSection, k -> new StringBuilder())
                        .append(remaining).append("\n");
            }
        }

        contentBuilders.forEach((section, builder) -> {
            String fullContent = builder.toString().trim();
            if (!fullContent.isEmpty()) section.setContent(fullContent);
        });

        return roots;
    }

    private List<Section> splitByBlankLines(String text) {
        String[] paragraphs = text.split("\\n\\s*\\n");
        List<Section> sections = new ArrayList<>();
        for (int i = 0; i < paragraphs.length; i++) {
            String content = paragraphs[i].trim();
            if (content.isEmpty()) continue;
            Section sec = new Section();
            sec.setTitle("段落" + (i + 1));
            sec.setLevel(1);
            sec.setContent(content);
            sec.setChildren(new ArrayList<>());
            sec.setBreadcrumbs(List.of(sec.getTitle()));
            sections.add(sec);
        }
        return sections;
    }

    private int calculateLevel(String title, Pattern pattern) {
        if (pattern == HEADING_PATTERNS[0]) {
            return determineMarkdownLevel(title);
        }
        return 1; // 其余标题均视为一级
    }

    private int determineMarkdownLevel(String headingLine) {
        int count = 0;
        for (char c : headingLine.toCharArray()) {
            if (c == '#') count++;
            else break;
        }
        return Math.max(1, count);
    }

    // ===================== 章节扁平化、合并、句子切分 =====================

    private List<Document> flattenSections(List<Section> sections, Document sourceDoc,
                                           int maxChunkSize, int minMergeSize, int overlapSize, int maxNumChunks) {
        // 处理孤立前言
        if (!sections.isEmpty()) {
            Section first = sections.get(0);
            if ("前言".equals(first.getTitle()) && first.getChildren().isEmpty()
                    && first.getContent() != null && first.getContent().length() < minMergeSize) {
                if (sections.size() > 1) {
                    Section next = sections.get(1);
                    next.setContent((first.getContent() + "\n" + next.getContent()).trim());
                    sections.removeFirst();
                }
            }
        }

        List<Section> processed = mergeSmallSections(sections, maxChunkSize, minMergeSize);
        List<Document> chunks = new ArrayList<>();

        for (Section sec : processed) {
            String parentContent = sec.getContent();

            // 父内容过短且含子节点：下放
            if (!sec.getChildren().isEmpty() && parentContent != null && parentContent.length() < minMergeSize) {
                Section firstChild = findFirstContentChild(sec.getChildren());
                if (firstChild != null) {
                    firstChild.setContent(parentContent + "\n" + firstChild.getContent());
                }
                parentContent = null;
            }

            if (parentContent != null && !parentContent.isEmpty()) {
                if (parentContent.length() <= maxChunkSize) {
                    chunks.add(createChunkFromSection(sec, parentContent, sourceDoc));
                } else {
                    // 仅当内容过长时才句子切分，且无重叠
                    List<String> sentences = splitIntoSentences(parentContent);
                    List<String> chunkTexts = groupSentences(sentences, maxChunkSize, 0);
                    for (String chunkText : chunkTexts) {
                        chunks.add(createChunkFromSection(sec, chunkText, sourceDoc));
                    }
                }
            }

            // 递归子章节
            if (!sec.getChildren().isEmpty()) {
                chunks.addAll(flattenSections(sec.getChildren(), sourceDoc,
                        maxChunkSize, minMergeSize, overlapSize, maxNumChunks));
            }
        }
        return chunks;
    }

    private Section findFirstContentChild(List<Section> children) {
        for (Section child : children) {
            if (child.getContent() != null && !child.getContent().isEmpty()) return child;
            Section found = findFirstContentChild(child.getChildren());
            if (found != null) return found;
        }
        return null;
    }

    private List<Section> mergeSmallSections(List<Section> sections, int maxChunkSize, int minMergeSize) {
        List<Section> result = new ArrayList<>();
        List<Section> buffer = new ArrayList<>();
        int bufferLen = 0;

        for (Section sec : sections) {
            boolean isLeaf = sec.getChildren().isEmpty();
            if (isLeaf && sec.getContent() != null && !sec.getContent().isEmpty()) {
                int len = sec.getContent().length();
                if (len < minMergeSize) {
                    if (bufferLen + len <= maxChunkSize) {
                        buffer.add(sec);
                        bufferLen += len;
                    } else {
                        if (!buffer.isEmpty()) {
                            result.add(createMergedSection(buffer));
                            buffer.clear();
                            bufferLen = 0;
                        }
                        buffer.add(sec);
                        bufferLen = len;
                    }
                } else {
                    if (!buffer.isEmpty()) {
                        result.add(createMergedSection(buffer));
                        buffer.clear();
                        bufferLen = 0;
                    }
                    result.add(sec);
                }
            } else {
                if (!buffer.isEmpty()) {
                    result.add(createMergedSection(buffer));
                    buffer.clear();
                    bufferLen = 0;
                }
                result.add(sec);
            }
        }
        if (!buffer.isEmpty()) result.add(createMergedSection(buffer));
        return result;
    }

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
            sb.append(sec.getTitle()).append("\n").append(sec.getContent()).append("\n");
        }
        merged.setContent(sb.toString().trim());
        return merged;
    }

    // ===================== 句子切分（无重叠） =====================

    private List<String> splitIntoSentences(String text) {
        if (!StringUtils.hasText(text)) return List.of();
        String[] parts = SENTENCE_BOUNDARY.split(text);
        List<String> sentences = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) sentences.add(trimmed);
        }
        return sentences;
    }

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
                if (currentLen == 0 || currentLen + sentLen + 1 <= maxSize) {
                    if (currentLen > 0) { sb.append(" "); currentLen++; }
                    sb.append(sent);
                    currentLen += sentLen;
                    j++;
                } else break;
            }
            if (!sb.isEmpty()) chunks.add(sb.toString().trim());
            i = j; // 无重叠：直接跳到结束位置
        }
        return chunks;
    }

    // ===================== 强制最小块合并 =====================

    private List<Document> enforceMinChunkSize(List<Document> chunks, int minMergeSize, Document sourceDoc) {
        if (chunks == null || chunks.isEmpty()) return new ArrayList<>();
        if (chunks.size() == 1) return chunks;

        List<Document> result = new ArrayList<>();
        Document current = chunks.get(0);

        for (int i = 1; i < chunks.size(); i++) {
            Document next = chunks.get(i);
            if (current.getText().length() < minMergeSize) {
                // 合并
                current = new Document(current.getText() + "\n" + next.getText(), current.getMetadata());
            } else {
                result.add(current);
                current = next;
            }
        }
        // 尾部短块向前合并
        if (current.getText().length() < minMergeSize && !result.isEmpty()) {
            Document last = result.remove(result.size() - 1);
            current = new Document(last.getText() + "\n" + current.getText(), last.getMetadata());
        }
        result.add(current);
        return result;
    }

    // ===================== 辅助方法 =====================

    private Document createChunkFromSection(Section section, String text, Document sourceDoc) {
        Map<String, Object> metadata = new HashMap<>(sourceDoc.getMetadata());
        metadata.put("section_title", section.getTitle());
        metadata.put("section_path", String.join(" > ", section.getBreadcrumbs()));
        return new Document(text, metadata);
    }

    private List<Document> enrichChunkMetadata(List<Document> chunks, int totalChunkCount) {
        if (chunks == null || chunks.isEmpty()) return List.of();
        List<Document> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            if (chunk == null || !StringUtils.hasText(chunk.getText())) continue;
            HashMap<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            metadata.put("chunkId", i + 1);
            metadata.put("chunkSize", totalChunkCount);
            String fileId = (String) metadata.getOrDefault("fileId", "default");
            result.add(Document.builder()
                    .id(fileId + ":" + String.format("%06d", i + 1))
                    .text(chunk.getText())
                    .metadata(metadata)
                    .build());
        }
        return result;
    }

    private void skipChunk(IngestionContext context) { /* 保持原逻辑不变 */ }

    private int readInt(JsonNode settings, String key, int defaultValue) {
        if (settings != null && settings.has(key) && settings.get(key).canConvertToInt())
            return settings.get(key).asInt();
        return defaultValue;
    }
}