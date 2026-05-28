import { marked } from "marked";
import hljs from "highlight.js/lib/common";
import katex from "katex";
import DOMPurify from "dompurify";
import { gfmHeadingId } from "marked-gfm-heading-id";
import generateToc from "marked-toc";

function escapeHtml(value) {
  return String(value || "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;")
    .replace(/`/g, "&#96;");
}

function validateHref(href) {
  if (!href) return false;
  const lower = String(href).trim().toLowerCase();
  return !/^(javascript:|data:|vbscript:|file:)/.test(lower);
}

// Marked plugins
marked.use(gfmHeadingId());
marked.setOptions({
  gfm: true,
  breaks: true,
  headerIds: true,
  mangle: false,
});

const renderer = {
  code(code, infostring) {
    const lang = (infostring || "").trim().split(/\s+/)[0] || "";
    try {
      if (lang && hljs.getLanguage(lang)) {
        const highlighted = hljs.highlight(code, {
          language: lang,
          ignoreIllegals: true,
        }).value;
        return `<pre class="hljs code-block" data-language="${escapeHtml(lang)}"><code class="language-${escapeHtml(lang)}">${highlighted}</code></pre>`;
      }
      const auto = hljs.highlightAuto(code);
      const language = auto.language || "";
      return `<pre class="hljs code-block" data-language="${escapeHtml(language)}"><code class="language-${escapeHtml(language)}">${auto.value}</code></pre>`;
    } catch (e) {
      return `<pre class="hljs code-block"><code>${escapeHtml(code)}</code></pre>`;
    }
  },
  link(href, title, text) {
    if (!validateHref(href)) return text;
    const titleAttr = title ? ` title="${escapeHtml(title)}"` : "";
    return `<a href="${escapeHtml(href)}"${titleAttr} target="_blank" rel="noopener noreferrer">${text}</a>`;
  },
  image(href, title, text) {
    const alt = escapeHtml(text || "");
    const titleAttr = title ? ` title="${escapeHtml(title)}"` : "";
    const src = escapeHtml(href || "");
    return `<img src="${src}" alt="${alt}" loading="lazy" class="zoomable" ${titleAttr} />`;
  },
};

marked.use({ renderer });

const tocPlaceholderPattern =
  /(?:<!--\s*toc\s*-->|<!--\s*TOC\s*-->|\[\[toc\]\]|\[toc\])/i;
const tocPlaceholderLinePattern =
  /^\s*(?:<!--\s*toc\s*-->|<!--\s*TOC\s*-->|\[\[toc\]\]|\[toc\])\s*$/gim;

function injectTocPlaceholders(md) {
  if (!tocPlaceholderPattern.test(md)) return md;

  let tocMarkdown = "";
  try {
    tocMarkdown = String(generateToc(md) || "");
  } catch {
    tocMarkdown = "";
  }

  const codeFenceRegex = /```[\s\S]*?```/g;
  const codeBlocks = [];
  const placeholder = (index) => `@@CODE_BLOCK_${index}@@`;

  const withoutFences = md.replace(codeFenceRegex, (match) => {
    const index = codeBlocks.length;
    codeBlocks.push(match);
    return placeholder(index);
  });

  let processed = withoutFences.replace(
    tocPlaceholderLinePattern,
    tocMarkdown.trim() ? tocMarkdown : "",
  );

  processed = processed.replace(/@@CODE_BLOCK_(\d+)@@/g, (match, index) => {
    return codeBlocks[Number(index)] || "";
  });

  return processed;
}

// Preprocess math fragments while preserving fenced code blocks and inline code
function renderWithMath(md) {
  const protectedRegex = /```[\s\S]*?```|`[^`\n]*`/g;
  const protectedBlocks = [];
  const placeholder = (i) => `@@MATH_PROTECTED_${i}@@`;

  const withoutProtected = md.replace(protectedRegex, (m) => {
    const idx = protectedBlocks.length;
    protectedBlocks.push(m);
    return placeholder(idx);
  });

  let processed = withoutProtected
    .replace(/\$\$([\s\S]+?)\$\$/g, (m, g1) => {
      try {
        return katex.renderToString(g1, {
          displayMode: true,
          throwOnError: false,
        });
      } catch {
        return `<pre class="katex-error">${escapeHtml(m)}</pre>`;
      }
    })
    .replace(/\$([^$\n]+?)\$/g, (m, g1) => {
      try {
        return katex.renderToString(g1, {
          displayMode: false,
          throwOnError: false,
        });
      } catch {
        return `<code>${escapeHtml(m)}</code>`;
      }
    });

  processed = processed.replace(
    /@@MATH_PROTECTED_(\d+)@@/g,
    (m, idx) => protectedBlocks[Number(idx)] || "",
  );
  return processed;
}

// Ensure a space after ATX heading markers only at the start of a line.
// Uses multiline flag to avoid breaking normal text containing #.
function ensureHeadingSpacing(md) {
  if (!md) return md;
  const codeFenceRegex = /```[\s\S]*?```/g;
  const codeBlocks = [];
  const placeholder = (i) => `@@CODE_BLOCK_${i}@@`;

  const withoutFences = md.replace(codeFenceRegex, (m) => {
    const idx = codeBlocks.length;
    codeBlocks.push(m);
    return placeholder(idx);
  });

  // Match heading markers at the beginning of a line, ensure a following space
  const processed = withoutFences.replace(/^(#{1,6})(?=[^\s#])/gm, "$1 ");

  const restored = processed.replace(
    /@@CODE_BLOCK_(\d+)@@/g,
    (m, idx) => codeBlocks[Number(idx)] || "",
  );
  return restored;
}

function fixBrokenInlineFence(md) {
  if (!md) return md;

  // 1. 保护完整的代码块和inline code不被打散
  const protectedRegex = /```[\s\S]*?```|`[^`\n]*`/g;
  const protectedBlocks = [];
  const placeholder = (i) => `@@FENCE_${i}@@`;

  const withoutProtected = md.replace(protectedRegex, (m) => {
    const idx = protectedBlocks.length;
    protectedBlocks.push(m);
    return placeholder(idx);
  });

  // 2. 处理不成对的反引号（奇数个反引号无法配对），替换为标记稍后还原
  let processed = withoutProtected
    .split("\n")
    .map((line) => {
      if (!line.trim()) return line;
      const rawBackticks = line.match(/(?<!\\)`/g);
      if (rawBackticks && rawBackticks.length % 2 !== 0) {
        const idx = protectedBlocks.length;
        protectedBlocks.push("`");
        return line
          .split(/(?<!\\)`/g)
          .map((part, i, arr) => (i < arr.length - 1 ? part + `@@FENCE_${idx}@@` : part))
          .join("");
      }
      return line;
    })
    .join("\n");

  // 3. 修复一个反引号紧接三个反引号导致的标记冲突：` ```... → 插入换行
  processed = processed.replace(/`(?=`{3,})/g, "`\n");

  // 4. 还原被保护的内容
  processed = processed.replace(
    /@@FENCE_(\d+)@@/g,
    (_, idx) => protectedBlocks[Number(idx)] || "",
  );

  return processed;
}

// Unescape common Markdown escapes (\\*, \\_, \\`, \\$) outside of code blocks and inline code.
function unescapeMarkdownEscapes(md) {
  if (!md) return md;
  const codeRegex = /(```[\s\S]*?```|`[^`]*`)/g;
  const blocks = [];
  const placeholder = (i) => `@@CODE_ESC_${i}@@`;

  const withoutCode = md.replace(codeRegex, (m) => {
    const i = blocks.length;
    blocks.push(m);
    return placeholder(i);
  });

  // Unescape backslash-escaped markdown characters
  const processed = withoutCode.replace(/\\([*_`$])/g, "$1");

  const restored = processed.replace(
    /@@CODE_ESC_(\d+)@@/g,
    (m, idx) => blocks[Number(idx)] || "",
  );
  return restored;
}

// Convert inline backtick spans that contain newlines into fenced code blocks.
// Improved: avoid wrapping text that already contains code block markers.
function convertInlineCodeWithNewlines(md) {
  if (!md) return md;
  const codeFenceRegex = /```[\s\S]*?```/g;
  const codeBlocks = [];
  const placeholder = (i) => `@@CODE_BLOCK_${i}@@`;

  const withoutFences = md.replace(codeFenceRegex, (m) => {
    const idx = codeBlocks.length;
    codeBlocks.push(m);
    return placeholder(idx);
  });

  // Protect single-line inline codes first so the multi-line regex
  // below cannot match from a closing backtick of one inline code
  // to the opening backtick of another across newlines.
  const inlineRegex = /`[^`\n]*`/g;
  const inlineBlocks = [];
  const withoutInline = withoutFences.replace(inlineRegex, (m) => {
    inlineBlocks.push(m);
    return `@@INLINE_CODE_${inlineBlocks.length - 1}@@`;
  });

  const processed = withoutInline.replace(/`([^`\n]*\n[^`]*)`/g, (m, g1) => {
    // If the captured content begins or ends with ```, it's likely a broken
    // code block marker, skip conversion to avoid doubling up.
    if (/^\s*```|```\s*$/.test(g1)) {
      return m;
    }
    const inner = String(g1).replace(/^\n+|\n+$/g, "");
    return "```text\n" + inner + "\n```";
  });

  const withInline = processed.replace(
    /@@INLINE_CODE_(\d+)@@/g,
    (_, idx) => inlineBlocks[Number(idx)] || "",
  );
  return withInline.replace(
    /@@CODE_BLOCK_(\d+)@@/g,
    (_, idx) => codeBlocks[Number(idx)] || "",
  );
}


// Auto-detect unfenced code blocks and wrap them in backtick fences.
// Uses heuristics: indentation, comments, braces, and known code keywords.
function autoFenceCodeBlocks(md) {
  if (!md) return md;

  const lines = md.split("\n");
  const output = [];
  const buf = [];
  let inFence = false;

  const codeKeywords = [
    "public", "private", "protected", "class", "interface", "enum",
    "extends", "implements", "import", "package", "return", "if", "else",
    "for", "while", "do", "switch", "case", "break", "continue",
    "try", "catch", "finally", "throw", "throws", "new", "this", "super",
    "static", "final", "abstract", "synchronized", "volatile", "transient",
    "def", "val", "var", "fun", "async", "await", "let", "const",
    "function", "using", "namespace", "type", "record", "struct",
    "int", "float", "double", "long", "boolean", "char", "byte", "short",
    "void", "string", "when", "object", "trait", "sealed",
    "data", "open", "inner", "override",
  ];
  const keywordPattern = new RegExp(
    "^\\s{0,3}(" + codeKeywords.join("|") + ")\\b"
  );

  function isCodeLine(line) {
    const trimmed = line.trim();
    if (!trimmed) return false;

    // --- Exclude common markdown patterns (checked before code patterns) ---

    // Markdown unordered list:   * text, - text, + text (0-3 spaces indent)
    if (/^\s{0,3}([-*+])\s/.test(trimmed)) return false;
    // Markdown ordered list:   1. text, 1) text
    if (/^\s{0,3}\d+[.)]\s/.test(trimmed)) return false;
    // Markdown blockquotes
    if (/^\s{0,3}>/.test(trimmed)) return false;
    // Markdown ATX headings
    if (/^\s{0,3}#{1,6}\s/.test(trimmed)) return false;
    // Markdown horizontal rules
    if (/^\s{0,3}[-*_]{3,}\s*$/.test(trimmed)) return false;
    // Markdown table rows (pipe-delimited)
    if (/^\s*\|/.test(trimmed) || /\|\s*$/.test(trimmed)) return false;

    // --- Code detection patterns ---

    // Comment lines
    if (/^\/\//.test(trimmed) || /^\/\*/.test(trimmed)) return true;

    // Block comment continuation lines (require leading indent to avoid bold/italic conflict)
    if (/^\s+\*\s/.test(line)) return true;

    // Single braces
    if (/^[\{\}]$/.test(trimmed)) return true;

    // Closing brace + keyword: } else if, } catch, } finally, } while
    if (/^}\s*(else|catch|finally|do|while)\b/.test(trimmed)) return true;

    // Lines ending in opening brace (Java/Kotlin style)
    if (/\{\s*$/.test(trimmed)) return true;

    // 4+ spaces indent (standard markdown code convention)
    if (/^\s{4,}/.test(line)) return true;

    // Code keyword at line start (allows 0-3 leading spaces)
    if (keywordPattern.test(line)) return true;

    // Type keyword + word chars + assignment: e.g., "intremaining = ..."
    if (/^\s{0,3}(?:int|float|double|long|boolean|char|byte|short|void|string)\w*\s*=/.test(trimmed)) return true;

    return false;
  }

  function flush() {
    if (buf.length === 0) return;
    // Single-line "brace-only" lines not worth fencing
    if (buf.length === 1 && /^\s*[\{\}]$/.test(buf[0])) {
      output.push(buf[0]);
      buf.length = 0;
      return;
    }
    const code = buf.join("\n");
    output.push("```\n" + code + "\n```");
    buf.length = 0;
  }

  for (const line of lines) {
    // Track existing fenced blocks so we don't re-fence their content
    if (/^```/.test(line.trim())) {
      flush();
      inFence = !inFence;
      output.push(line);
      continue;
    }
    if (inFence) {
      output.push(line);
      continue;
    }

    if (isCodeLine(line)) {
      buf.push(line);
    } else if (!line.trim() && buf.length > 0) {
      buf.push(line); // empty line inside code
    } else {
      flush();
      output.push(line);
    }
  }
  flush();

  return output.join("\n");
}


export function renderAssistantMarkdown(rawText) {
  let content = String(rawText || "").replace(/\r\n/g, "\n");
  if (!content.trim()) return "";

  // Decode HTML entities (e.g. &#42;) that would prevent markdown parsing
  try {
    if (typeof document !== "undefined" && document.createElement) {
      const ta = document.createElement("textarea");
      ta.innerHTML = content;
      content = ta.value || ta.textContent || content;
    }
  } catch (e) {
    // ignore
  }

  // Remove zero-width characters
  content = content.replace(/\u200B|\u200C|\u200D|\uFEFF/g, "");

  // Convert non-standard list symbols (\u00B7 \u2022 \u25CF \u25AA etc.) to standard markdown -
  content = content.replace(/^(\s*)[\u00B7\uF0B7\u2022\u25CF\u25AA] /gm, "$1- ");

  // Step A1: Insert a space between inline code and any immediately-following
  // backtick sequence (1+ backticks). Without this space, marked cannot find
  // a valid closing delimiter because the closing backtick would be part of a
  // longer backtick string, violating GFM inline code rules.
  // Must run BEFORE step B so the inline-code closer is not consumed as
  // part of a 3+-backtick sequence.
  // e.g.  `code```text  \u2192  `code` ``text
  content = content.replace(/(?<=^|\s)(`[^`\n]+`)(`+)/g, '$1 $2');

  // Step A2: Replace triple-backtick-after-inline-code with a placeholder
  // to prevent fixBrokenInlineFence from breaking it (matching 2 backticks
  // as empty inline code, then parity check adding more).
  // e.g.  `code` ```text  \u2192  `code` @@XY_TRIPLE_BT@@text
  content = content.replace(/(?<=^|\s)(`[^`\n]+`)\s*(```)(\w*)/g, '$1 @@XY_TRIPLE_BT@@$3');

  // Step B: Fix lines with 3+ opening backticks but a single (or no) closing
  // backtick. These are likely broken inline code (triple-backtick typo), not
  // fenced blocks.  e.g.  ```O(1)...`  \u2192  `O(1)...`
  content = content.replace(/(`{3,})([^`\n]*)`/g, (m, open, inner) => {
    if (open.length >= 3) return "`" + inner + "`";
    return m;
  });

  const fixedFence = fixBrokenInlineFence(content);
  const normalizedHeadings = ensureHeadingSpacing(fixedFence);
  const autoFenced = autoFenceCodeBlocks(normalizedHeadings);
  const normalizedInlineCode =
    convertInlineCodeWithNewlines(autoFenced);
  const unescaped = unescapeMarkdownEscapes(normalizedInlineCode);
  const withToc = injectTocPlaceholders(unescaped);
  const withMath = renderWithMath(withToc);
  const html = marked.parse(withMath);

  // Sanitize final HTML, preserve necessary attributes for custom elements
  let result;
  try {
    result = DOMPurify.sanitize(html, {
      ALLOWED_ATTR: [
        "href",
        "target",
        "rel",
        "src",
        "alt",
        "loading",
        "class",
        "data-language",
        "title",
        "id",
      ],
      ALLOW_DATA_ATTR: true,
    });
  } catch {
    result = html;
  }

  // Restore triple-backtick placeholders as literal HTML
  result = result.replace(/@@XY_TRIPLE_BT@@/g, "&#96;&#96;&#96;");

  // Wrap consecutive images in a row container
  result = result.replace(
    /((?:<img[^>]*class="zoomable"[^>]*>\s*){2,})/g,
    '<div class="image-row">$1</div>',
  );

  return result;
}

// Add copy buttons to code blocks (call after injecting HTML)
export function initCodeCopyButtons(container) {
  if (!container || !container.querySelectorAll) return;
  container.querySelectorAll("pre.hljs.code-block").forEach((pre) => {
    if (pre.__xyai_copy_init) return;
    pre.__xyai_copy_init = true;

    const lang = (pre.dataset && pre.dataset.language) || "";
    const wrapper = document.createElement("div");
    wrapper.className = "code-block-wrapper";

    const header = document.createElement("div");
    header.className = "code-block-header";

    const langLabel = document.createElement("span");
    langLabel.className = "code-lang-label";
    langLabel.textContent = lang || "code";

    const copyBtn = document.createElement("button");
    copyBtn.className = "code-copy-btn";
    copyBtn.innerHTML =
      '<svg class="copy-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>' +
      '<span class="copy-label">复制</span>';
    copyBtn.addEventListener("click", () => {
      const code = pre.querySelector("code");
      const text = code ? code.textContent : pre.textContent;
      navigator.clipboard
        .writeText(text)
        .then(() => {
          copyBtn.querySelector(".copy-label").textContent = "已复制";
          setTimeout(() => {
            copyBtn.querySelector(".copy-label").textContent = "复制";
          }, 1500);
        })
        .catch(() => {});
    });

    header.appendChild(langLabel);
    header.appendChild(copyBtn);

    const divider = document.createElement("div");
    divider.className = "code-block-divider";

    pre.parentNode.insertBefore(wrapper, pre);
    wrapper.appendChild(header);
    wrapper.appendChild(divider);
    wrapper.appendChild(pre);
  });
}

// Simple image zoom helper (call after injecting HTML)
export function attachImageZoom(container) {
  if (!container || !container.querySelectorAll) return;
  container.querySelectorAll("img.zoomable").forEach((img) => {
    if (img.__xyai_zoom_init) return;
    img.__xyai_zoom_init = true;
    img.style.cursor = "zoom-in";
    img.addEventListener("click", () => {
      try {
        const overlay = document.createElement("div");
        overlay.style.position = "fixed";
        overlay.style.inset = "0";
        overlay.style.background = "rgba(0,0,0,0.85)";
        overlay.style.display = "flex";
        overlay.style.alignItems = "center";
        overlay.style.justifyContent = "center";
        overlay.style.zIndex = 99999;
        const im = document.createElement("img");
        im.src = img.src;
        im.style.maxWidth = "95%";
        im.style.maxHeight = "95%";
        im.style.boxShadow = "0 10px 40px rgba(0,0,0,0.5)";
        im.style.borderRadius = "6px";
        overlay.appendChild(im);
        overlay.addEventListener(
          "click",
          () => {
            if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
          },
          { once: true },
        );
        document.body.appendChild(overlay);
      } catch (e) {
        // ignore
      }
    });
  });
}
