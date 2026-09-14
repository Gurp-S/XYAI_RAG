import { marked } from "marked";
import hljs from "highlight.js/lib/common";
import { gfmHeadingId } from "marked-gfm-heading-id";
import generateToc from "marked-toc";

function escapeHtml(v) { return String(v||"").replace(/&/g,"&amp;").replace(/</g,"&lt;").replace(/>/g,"&gt;").replace(/"/g,"&quot;").replace(/'/g,"&#39;").replace(/`/g,"&#96;"); }
function validateHref(h) { return !/^(javascript:|data:|vbscript:|file:)/.test(String(h||"").trim().toLowerCase()); }

marked.use(gfmHeadingId());
marked.setOptions({gfm:true,breaks:true,headerIds:true,mangle:false});

const renderer = {
  code(code,infostring){
    const lang=(infostring||"").trim().split(/\s+/)[0]||"";
    try {
      if(lang&&hljs.getLanguage(lang)){
        const h=hljs.highlight(code,{language:lang,ignoreIllegals:true}).value;
        return `<pre class="hljs code-block" data-language="${escapeHtml(lang)}"><code class="language-${escapeHtml(lang)}">${h}</code></pre>`;
      }
      const auto=hljs.highlightAuto(code);
      return `<pre class="hljs code-block" data-language="${escapeHtml(auto.language||'')}"><code class="language-${escapeHtml(auto.language||'')}">${auto.value}</code></pre>`;
    } catch(e) { return `<pre class="hljs code-block"><code>${escapeHtml(code)}</code></pre>`; }
  },
  link(href,title,text){ if(!validateHref(href)) return text; return `<a href="${escapeHtml(href)}"${title?` title="${escapeHtml(title)}"`:""} target="_blank" rel="noopener noreferrer">${text}</a>`; },
  image(href,title,text){ return `<img src="${escapeHtml(href||"")}" alt="${escapeHtml(text||"")}" loading="lazy" class="zoomable"${title?` title="${escapeHtml(title)}"`:""} />`; },
};
marked.use({renderer});

function injectTocPlaceholders(md){ return md; }
function renderWithMath(md){
  const b=[]; const w=md.replace(/```[\s\S]*?```|`[^`\n]*`/g,m=>(b.push(m),`@@MP_${b.length-1}@@`));
  return w.replace(/\$\$([\s\S]+?)\$\$/g,'<div class="math">[MATH]</div>').replace(/\$([^$\n]+?)\$/g,'<span class="math">[MATH]</span>').replace(/@@MP_(\d+)@@/g,(_,i)=>b[Number(i)]||"");
}
function ensureHeadingSpacing(md){
  if(!md)return md; const b=[]; const w=md.replace(/```[\s\S]*?```/g,m=>(b.push(m),`@@CB_${b.length-1}@@`));
  return w.replace(/^(#{1,6})(?=[^\s#])/gm,"$1 ").replace(/@@CB_(\d+)@@/g,(_,i)=>b[Number(i)]||"");
}
function fixBrokenInlineFence(md){
  if(!md)return md; const b=[]; const w=md.replace(/```[\s\S]*?```|`[^`\n]*`/g,m=>(b.push(m),`@@FB_${b.length-1}@@`));
  let p=w.split("\n").map(l=>{
    if(!l.trim())return l; const bt=l.match(/(?<!\\)`/g); if(bt&&bt.length%2!==0){ b.push("`"); const i=b.length-1; return l.split(/(?<!\\)`/g).map((P,I,A)=>I<A.length-1?P+`@@FB_${i}@@`:P).join(""); }
    return l;
  }).join("\n");
  p=p.replace(/`(?=`{3,})/g,"`\n");
  return p.replace(/@@FB_(\d+)@@/g,(_,i)=>b[Number(i)]||"");
}
function unescapeMarkdownEscapes(md){
  if(!md)return md; const b=[]; const w=md.replace(/(```[\s\S]*?```|`[^`]*`)/g,m=>(b.push(m),`@@CE_${b.length-1}@@`));
  return w.replace(/\\([*_`$])/g,"$1").replace(/@@CE_(\d+)@@/g,(_,i)=>b[Number(i)]||"");
}
function convertInlineCodeWithNewlines(md){
  if(!md)return md;
  const cb=[]; const wf=md.replace(/```[\s\S]*?```/g,m=>(cb.push(m),`@@CBLK_${cb.length-1}@@`));
  const ib=[]; const wi=wf.replace(/`[^`\n]*`/g,m=>(ib.push(m),`@@IC_${ib.length-1}@@`));
  const p=wi.replace(/`([^`\n]*\n[^`]*)`/g,(m,g1)=>{if(/^\s*```|```\s*$/.test(g1))return m; return "```text\n"+String(g1).replace(/^\n+|\n+$/g,"")+"\n```";});
  return p.replace(/@@IC_(\d+)@@/g,(_,i)=>ib[Number(i)]||"").replace(/@@CBLK_(\d+)@@/g,(_,i)=>cb[Number(i)]||"");
}

function renderAssistantMarkdown(rawText) {
  let c = String(rawText||"").replace(/\r\n/g,"\n");
  if(!c.trim()) return "";
  c = c.replace(/​|‌|‍|﻿/g,"");

  c = c.replace(/(?<=^|\s)(`[^`\n]+`)(`+)/g, '$1 $2');
  c = c.replace(/(?<=^|\s)(`[^`\n]+`)\s*(```)(\w*)/g, '$1 @@XY_TRIPLE_BT@@$3');
  c = c.replace(/(`{3,})([^`\n]*)`/g, (m,open,inner) => open.length>=3 ? "`"+inner+"`" : m);

  const ff = fixBrokenInlineFence(c);
  const nh = ensureHeadingSpacing(ff);
  const nic = convertInlineCodeWithNewlines(nh);
  const un = unescapeMarkdownEscapes(nic);
  const toc = injectTocPlaceholders(un);
  const math = renderWithMath(toc);

  const html = marked.parse(math);
  return html.replace(/@@XY_TRIPLE_BT@@/g,"&#96;&#96;&#96;");
}

const testCases = [
  {
    name: "volatile vs synchronized",
    md: "2. 作用范围\nvolatile：仅修饰变量（如 `volatile int x;` ```\n）\n**\n`synchronized`：修饰方法或代码块**（锁对象为实例、类或自定义对象）。",
  },
  {
    name: "inline code + triple backtick + lang",
    md: "例如 `0x61c88647```text 这是传统方法。",
  },
  {
    name: "triple-open + single-close",
    md: "扩容时 ````O(1)...` 这句话对吗？",
  },
];

for (const {name, md} of testCases) {
  console.log(`\n=== ${name} ===`);
  console.log("INPUT:", JSON.stringify(md));
  try {
    const html = renderAssistantMarkdown(md);
    console.log("HTML:", html);
  } catch(e) { console.log("ERROR:", e.message); }
}
