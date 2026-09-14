package com.XYai.myai.rag.etlpipeline.consumers;

import com.XYai.myai.rag.aop.annotation.RagTraceNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文本清洗节点
 */
@Slf4j
@Component
public class TextCleaner {

    private static final char BOM = '\uFEFF';
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]");
    private static final Pattern MULTIPLE_NEWLINES = Pattern.compile("\\n{3,}");
    /** 仅匹配真正的 HTML 标签形式（标签名以字母开头、不跨行），避免 "<100ms" 这类比较符号吞噬中间正文 */
    private static final Pattern HTML_TAG = Pattern.compile("</?[a-zA-Z][a-zA-Z0-9]*(?:\\s[^>\\n]*)?/?>");
    private static final Pattern OUTER_XML_TAG = Pattern.compile("^<([a-zA-Z][\\w-]*)>[\\s\\S]*</\\1>$");
    private static final Pattern OUTER_JSON_OBJECT = Pattern.compile("^\\{[\\s\\S]*}$");
    private static final Pattern FENCED_CODE_BLOCK = Pattern.compile("```[^`]*```", Pattern.DOTALL);

    // ===== 注入防护清洗（OWASP LLM01 间接注入：不可见字符与隐藏内容可携带载荷） =====
    /** 零宽字符与不可见分隔符：\u200B-\u200F, \u2060-\u206F, \u00AD */
    private static final Pattern INVISIBLE_CHARS = Pattern.compile("[\\u200B-\\u200F\\u2060-\\u206F\\u00AD]");
    /** Unicode 双向控制字符（可视觉重排隐藏真实指令）：\u202A-\u202E, \u2066-\u2069 */
    private static final Pattern BIDI_CONTROL_CHARS = Pattern.compile("[\\u202A-\\u202E\\u2066-\\u2069]");
    /** HTML 注释（渲染不可见但对模型可见，常被用于藏注入指令） */
    private static final Pattern HTML_COMMENT = Pattern.compile("<!--[\\s\\S]*?-->");
    /** 全角/同形字符归一化前，先保护代码块（归一化会改写代码内容） */

    @RagTraceNode(name = "清洗", type = "上传管道", taskIdArg = "etlNode")
    public Document execute(Document document) {
        log.info("TextClean start");
        String rawText = document.getText();
        if (rawText == null || rawText.isEmpty()) {
            log.warn("输入文档文本为空，跳过清洗");
            return document;
        }

        String cleanText = fullClean(rawText);
        log.debug("清洗前长度: {}，清洗后长度: {}", rawText.length(), cleanText.length());

        Map<String, Object> newMeta = new HashMap<>(document.getMetadata());
        newMeta.put("cleaned", true);

        return Document.builder()
                .id(document.getId())
                .text(cleanText)
                .metadata(newMeta)
                .build();
    }

    private String fullClean(String raw) {
        String text = raw;

        // 1. 移除 BOM
        text = stripBom(text);

        // 2. 移除控制字符
        text = CONTROL_CHARS.matcher(text).replaceAll("");

        // 2.5 注入防护：移除零宽字符与双向控制字符（对人类不可见、对模型可见）
        text = INVISIBLE_CHARS.matcher(text).replaceAll("");
        text = BIDI_CONTROL_CHARS.matcher(text).replaceAll("");

        // 3. 剥离外层包裹（若存在）
        text = stripOuterWrapper(text);

        // 3.5 注入防护：移除 HTML 注释（渲染不可见但会进入模型上下文）
        text = HTML_COMMENT.matcher(text).replaceAll("");

        // 4. 保护所有代码块 -> 用占位符替换
        Map<String, String> codeBlocks = new HashMap<>();
        int[] counter = {0};
        String protectedText = FENCED_CODE_BLOCK.matcher(text).replaceAll(mr -> {
            String placeholder = "%%CODEBLOCK_" + counter[0]++ + "%%";
            codeBlocks.put(placeholder, mr.group());
            return placeholder;
        });

        // 4.5 NFKC 归一化：全角/同形字符折叠为标准形（防同形字混淆，代码块已隔离）
        protectedText = java.text.Normalizer.normalize(protectedText, java.text.Normalizer.Form.NFKC);

        // 5. 解码 HTML 实体（仅在非代码块区域，但已用占位符隔离）
        protectedText = HtmlUtils.htmlUnescape(protectedText);

        // 6. 移除残留 HTML 标签（同上）
        protectedText = HTML_TAG.matcher(protectedText).replaceAll("");

        // 7. 压缩连续空行
        protectedText = MULTIPLE_NEWLINES.matcher(protectedText).replaceAll("\n\n");

        // 8. 去除行首尾空白
        protectedText = Arrays.stream(protectedText.split("\\n"))
                .map(String::strip)
                .collect(Collectors.joining("\n"))
                .strip();

        // 9. 还原代码块
        for (Map.Entry<String, String> entry : codeBlocks.entrySet()) {
            protectedText = protectedText.replace(entry.getKey(), entry.getValue());
        }

        return protectedText;
    }

    private String stripBom(String text) {
        return (text.charAt(0) == BOM) ? text.substring(1) : text;
    }

    private String stripOuterWrapper(String text) {
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return text;

        // 依次尝试 Markdown 代码块、XML、JSON 包裹
        String result = stripMarkdownCodeBlock(trimmed);
        if (result != null) return result;

        result = stripXmlWrapper(trimmed);
        if (result != null) return result;

        result = stripJsonWrapper(trimmed);
        if (result != null) return result;

        return text;
    }

    /**
     * Markdown 代码块包裹：```任意语言标识
     */
    private String stripMarkdownCodeBlock(String text) {
        if (!text.startsWith("```") || !text.endsWith("```")) return null;

        String[] lines = text.split("\\r?\\n");
        if (lines.length < 2) return null;

        String firstLine = lines[0].strip();
        String lastLine = lines[lines.length - 1].strip();
        if (!lastLine.equals("```")) return null;

        String[] inner = Arrays.copyOfRange(lines, 1, lines.length - 1);
        String innerText = String.join("\n", inner).trim();
        if (innerText.isEmpty()) return text;

        String prefix;
        if (firstLine.length() > 3) {
            String lang = firstLine.substring(3).strip();
            prefix = "> 原始文档以代码块包裹，语言标记：**" + lang + "**\n\n";
        } else {
            prefix = "> 原始文档被代码块包裹，已自动还原结构。\n\n";
        }
        return prefix + innerText;
    }

    /**
     * XML/HTML 标签包裹：<tag> ... </tag>
     */
    private String stripXmlWrapper(String text) {
        if (!OUTER_XML_TAG.matcher(text).matches()) return null;

        int startTagEnd = text.indexOf('>');
        if (startTagEnd == -1) return null;

        String tagName = text.substring(1, startTagEnd).trim();
        int endTagStart = text.lastIndexOf("</" + tagName + ">");
        if (endTagStart == -1) return null;

        String inner = text.substring(startTagEnd + 1, endTagStart).trim();
        if (inner.isEmpty()) return text;

        String prefix = "> 原始文档由 XML 标签 `<" + tagName + ">` 包裹，已自动还原。\n\n";
        return prefix + inner;
    }

    /**
     * JSON 对象包裹：{ "key": "value" } ，仅当内部只有一个长文本值时才剥离
     */
    private String stripJsonWrapper(String text) {
        if (!OUTER_JSON_OBJECT.matcher(text).matches()) return null;

        String inner = text.substring(1, text.length() - 1).strip();
        int colon = inner.indexOf(':');
        if (colon == -1) return null;

        String key = inner.substring(0, colon).strip();
        String value = inner.substring(colon + 1).strip();

        // 去掉外层引号
        if ((value.startsWith("\"") && value.endsWith("\"")) ||
                (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }

        // 值太短或可能为嵌套 JSON 则不处理
        if (value.length() < 20 || value.trim().startsWith("{")) {
            return null;
        }

        String prefix = "> 原始文档被 JSON 对象包裹（键：\"" + key + "\"），已自动还原内容。\n\n";
        return prefix + value;
    }
}