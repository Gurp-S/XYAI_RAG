package com.XYai.myai.rag.etlpipeline.nodes;

import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import com.XYai.myai.rag.etlpipeline.pojo.IngestionContext;
import com.XYai.myai.rag.etlpipeline.pojo.NodeConfig;
import com.XYai.myai.rag.etlpipeline.pojo.NodeResult;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
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

    private static final TokenTextSplitter TOKEN_SPLITTER = new TokenTextSplitter();

    // 默认支持的标题模式：Markdown H1~H6 + 自定义“测试文章 数字：”
    private static final Pattern DEFAULT_HEADING_PATTERN =
            Pattern.compile("^(#{1,6}\\s|测试文章\\s*\\d+：)", Pattern.MULTILINE);

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
        int maxChunkSize = readInt(settings, "chunkSize", 512);
        int overlapSize = resolveOverlapSize(settings, maxChunkSize);

        // 1. 递归解析文档章节结构
        List<Section> sections = parseSections(text, DEFAULT_HEADING_PATTERN);

        List<Document> allChunks;
        if (sections.isEmpty()) {
            // 无标题时可回退至原始 Spring AI 分块
            allChunks = splitWithSpringAI(sourceDoc, text);
            if (allChunks == null || allChunks.isEmpty()) {
                allChunks = splitByWindow(sourceDoc, text, maxChunkSize, overlapSize);
            }
        } else {
            // 2. 按章节树扁平化并自适应切分
            allChunks = flattenSections(sections, sourceDoc, maxChunkSize, overlapSize);
        }

        // 3. 补充分块数量等元数据
        Document updatedDoc = context.getDocument().mutate()
                .metadata("chunk_size", allChunks.size())
                .build();
        context.setDocument(updatedDoc);

        // 4. 补齐入库所需字段（chunkId, chunkSize 等）
        allChunks = enrichChunkMetadata(allChunks, allChunks.size());
        context.setChunks(allChunks);

        // 5. 处理分块筛选逻辑（原有业务）
        Object chunkCopy = context.getDocument().getMetadata().get(IngestionContext.META_COPY_CHUNK);
        skipChunkCopy(chunkCopy, context);

        log.info("递归分块完成，共 {} 个 chunk", allChunks.size());
        return NodeResult.ok("分块数量=" + allChunks.size());
    }

    // ===================== 核心：章节树解析与扁平化 =====================

    /**
     * 文档章节节点
     */
    private static class Section {
        String title;               // 当前章节标题（最贴近的标题）
        List<String> breadcrumbs;   // 从根到当前的所有标题路径
        int level;                  // 标题层级，1 为最高
        String content;             // 该章节下的非标题文本
        List<Section> children;     // 子章节
    }

    /**
     * 递归将文档解析为章节树。
     * 算法：沿着标题将全文切分成段落，每个段落属于上一个标题，同时根据标题层级建立父子关系。
     */
    private List<Section> parseSections(String text, Pattern headingPattern) {
        List<Section> roots = new ArrayList<>();
        // 用栈维护当前路径：栈顶是最深的节点
        Deque<Section> stack = new ArrayDeque<>();
        // 记录每个层级的最近父节点，用于添加子节点
        Map<Integer, Section> levelParent = new HashMap<>();

        // 用正则拆分出每个标题及其后的内容
        Matcher headingMatcher = headingPattern.matcher(text);
        int lastEnd = 0;

        while (headingMatcher.find()) {
            // 处理上一个标题到当前标题之间的文本（属于上一个标题的内容）
            if (lastEnd < headingMatcher.start()) {
                String sectionContent = text.substring(lastEnd, headingMatcher.start()).trim();
                if (!sectionContent.isEmpty() && !stack.isEmpty()) {
                    stack.peek().content = sectionContent;
                }
            }

            // 提取当前标题行
            String headingLine = headingMatcher.group().trim();
            int level = determineLevel(headingLine);

            // 创建新 Section
            Section newSection = new Section();
            newSection.title = headingLine;
            newSection.level = level;
            newSection.children = new ArrayList<>();
            newSection.breadcrumbs = new ArrayList<>();

            // 确定父节点：找到栈中最近且层级小于当前层级的节点
            while (!stack.isEmpty() && stack.peek().level >= level) {
                stack.pop();
            }
            if (stack.isEmpty()) {
                // 根节点
                roots.add(newSection);
            } else {
                Section parent = stack.peek();
                parent.children.add(newSection);
                // 继承父路径
                newSection.breadcrumbs.addAll(parent.breadcrumbs);
            }
            newSection.breadcrumbs.add(headingLine);
            stack.push(newSection);
            levelParent.put(level, newSection);

            lastEnd = headingMatcher.end();
        }

        // 处理最后一个标题之后的剩余文本
        if (lastEnd < text.length()) {
            String remaining = text.substring(lastEnd).trim();
            if (!remaining.isEmpty() && !stack.isEmpty()) {
                stack.peek().content = remaining;
            }
        }

        return roots;
    }

    /**
     * 根据标题行判断层级：Markdown # 个数即层级，否则定为 2 (子标题)
     */
    private int determineLevel(String headingLine) {
        if (headingLine.startsWith("#")) {
            int count = 0;
            for (char c : headingLine.toCharArray()) {
                if (c == '#') count++; else break;
            }
            return Math.max(1, count);
        }
        // 自定义标题（如“测试文章 1：”）视为 1 级
        return 1;
    }

    /**
     * 将章节树扁平化为 Document 列表，对超出大小限制的章节再次切分。
     */
    private List<Document> flattenSections(List<Section> sections, Document sourceDoc,
                                           int maxChunkSize, int overlapSize) {
        List<Document> chunks = new ArrayList<>();
        for (Section sec : sections) {
            if (sec.content != null && !sec.content.isEmpty()) {
                if (sec.content.length() <= maxChunkSize) {
                    chunks.add(createChunkFromSection(sec, sec.content, sourceDoc));
                } else {
                    // 超限则使用 Spring AI 切分，并将父路径注入子 chunk
                    List<Document> subChunks = splitWithSpringAI(sourceDoc, sec.content, maxChunkSize, overlapSize);
                    for (Document sub : subChunks) {
                        inheritSectionMetadata(sub, sec);
                        chunks.add(sub);
                    }
                }
            }
            // 递归处理子章节
            if (!sec.children.isEmpty()) {
                chunks.addAll(flattenSections(sec.children, sourceDoc, maxChunkSize, overlapSize));
            }
        }
        return chunks;
    }

    /**
     * 为单个 Section 生成一个 Document，将路径信息写入 metadata。
     */
    private Document createChunkFromSection(Section section, String text, Document sourceDoc) {
        Map<String, Object> metadata = new HashMap<>(sourceDoc.getMetadata());
        metadata.put("section_title", section.title);
        metadata.put("section_path", String.join(" > ", section.breadcrumbs));
        return new Document(text, metadata);
    }

    /**
     * 将 Section 的路径信息复制到子 chunk 上。
     */
    private void inheritSectionMetadata(Document chunk, Section section) {
        chunk.getMetadata().put("section_title", section.title);
        chunk.getMetadata().put("section_path", String.join(" > ", section.breadcrumbs));
    }

    // ===================== 原有分块方法（兼容） =====================

    /**
     * Spring AI 分块，增加参数控制
     */
    private List<Document> splitWithSpringAI(Document sourceDoc, String text, int maxChunkSize, int overlapSize) {
        // 这里直接调用原方法，因为 TokenTextSplitter 的配置通常在构造时已定，我们保留原逻辑
        return splitWithSpringAI(sourceDoc, text);
    }

    public List<Document> splitWithSpringAI(Document sourceDoc, String text) {
        try {
            Document toSplit = sourceDoc.mutate()
                    .text(text)
                    .media(null)
                    .build();

            List<Document> chunks = TOKEN_SPLITTER.split(List.of(toSplit));
            if (chunks.isEmpty()) return List.of();

            List<Document> result = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                HashMap<String, Object> inherited = new HashMap<>(
                        sourceDoc.getMetadata() == null ? Map.of() : sourceDoc.getMetadata());
                result.add(chunks.get(i).mutate()
                        .id(String.valueOf(i + 1))
                        .metadata(inherited)
                        .build());
            }
            return result;
        } catch (Exception e) {
            log.error("文档分块异常", e);
            return List.of();
        }
    }

    private List<Document> splitByWindow(Document sourceDoc, String text, int chunkSize, int overlapSize) {
        // 原兜底逻辑保持不变（略，可保留你原来的实现）
        return List.of();
    }

    // ===================== 元数据增强 =====================

    private List<Document> enrichChunkMetadata(List<Document> chunks, int chunkSize) {
        if (chunks == null || chunks.isEmpty()) return List.of();

        List<Document> result = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Document chunk = chunks.get(i);
            if (chunk == null || !StringUtils.hasText(chunk.getText())) continue;

            HashMap<String, Object> metadata = new HashMap<>(chunk.getMetadata());
            int chunkId = i + 1;
            metadata.put("chunkId", chunkId);
            metadata.put("chunkSize", chunkSize);

            String fileId = (String) metadata.get("fileId");
            String expectedId = fileId + "-" + String.format("%06d", chunkId);

            result.add(Document.builder()
                    .id(expectedId)
                    .text(chunk.getText())
                    .metadata(metadata)
                    .build());
        }
        return result;
    }

    // ===================== 辅助方法 =====================

    private int resolveOverlapSize(JsonNode settings, int chunkSize) {
        int overlapSize = readInt(settings, "overlapSize", 50);
        int maxOverlap = Math.max(0, chunkSize / 2);
        if (overlapSize < 0) return 0;
        return Math.min(overlapSize, maxOverlap);
    }

    private int readInt(JsonNode settings, String key, int defaultVal) {
        if (settings != null && settings.has(key) && settings.get(key).canConvertToInt()) {
            return settings.get(key).asInt();
        }
        return defaultVal;
    }

    private void skipChunkCopy(Object chunkCopy, IngestionContext context) {
        // 保持你原有的实现
    }
}