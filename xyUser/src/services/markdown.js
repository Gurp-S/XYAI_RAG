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

  const processed = withoutFences.replace(/`([^`]*\n[^`]*)`/g, (m, g1) => {
    // If the captured content begins or ends with ```, it's likely a broken
    // code block marker, skip conversion to avoid doubling up.
    if (/^\s*```|```\s*$/.test(g1)) {
      return m;
    }
    const inner = String(g1).replace(/^\n+|\n+$/g, "");
    return "```text\n" + inner + "\n```";
  });

  return processed.replace(
    /@@CODE_BLOCK_(\d+)@@/g,
    (m, idx) => codeBlocks[Number(idx)] || "",
  );
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

  // Fix lines with 3+ opening backticks but a single (or no) closing backtick.
  // These are likely broken inline code (triple-backtick typo), not fenced blocks.
  // e.g.  ```O(1)...`  \u2192  `O(1)...`   (opening 3+, closing 1)
  content = content.replace(/(`{3,})([^`\n]*)`/g, (m, open, inner) => {
    if (open.length >= 3) return "`" + inner + "`";
    return m;
  });

  // Prevent inline-code-followed-by-triple-backtick from creating unintended
  // fenced code blocks that consume the rest of the document.
  // e.g.  `0x61c88647```text  \u2192  `0x61c88647` @@XY_TRIPLE_BT@@text
  content = content.replace(/(`[^`\n]+`)(```)/g, '$1 @@XY_TRIPLE_BT@@');

  const fixedFence = fixBrokenInlineFence(content);
  const normalizedHeadings = ensureHeadingSpacing(fixedFence);
  const normalizedInlineCode =
    convertInlineCodeWithNewlines(normalizedHeadings);
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
