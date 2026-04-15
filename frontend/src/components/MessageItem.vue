<template>
  <div class="message-wrapper" :class="role">
    <div class="avatar">{{ avatarText }}</div>
    <div class="message-content">
      <div v-if="role === 'assistant' && fromName" class="sender-name">{{ fromName }}</div>

      <div v-if="role === 'assistant' && rag" class="rag-tag">
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="2"
        >
          <circle cx="11" cy="11" r="8"></circle>
          <line x1="21" y1="21" x2="16.65" y2="16.65"></line>
        </svg>
        已检索企业知识库 (共命中 {{ ragData?.length || 0 }} 段相关切片)
      </div>

      <!-- RAG 引用来源显示 -->
      <div v-if="ragData && ragData.length > 0" class="rag-sources">
        <details>
          <summary>查看引用来源</summary>
          <ul>
            <li v-for="(item, idx) in ragData" :key="idx">
              <span class="source-index">[{{ idx + 1 }}]</span>
              <span class="source-content">{{ item.content || item }}</span>
            </li>
          </ul>
        </details>
      </div>

      <!-- MCP 工具调用状态 -->
      <div v-if="mcpStatus" class="mcp-status" :class="mcpStatus">
        <span v-if="mcpStatus === 'running'" class="mcp-loading-icon"></span>
        {{ mcpStatusText }}
      </div>

      <div
        v-if="text && role === 'assistant' && !isStreaming"
        class="message assistant-markdown"
        ref="markdownEl"
        v-html="assistantMarkdownHtml"
      ></div>
      <div v-else-if="text" class="message">{{ text }}</div>

      <!-- 错误状态与重试按钮 -->
      <div v-if="error" class="error-container">
        <div class="error-message">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            class="error-icon"
          >
            <circle cx="12" cy="12" r="10"></circle>
            <line x1="12" y1="8" x2="12" y2="12"></line>
            <line x1="12" y1="16" x2="12.01" y2="16"></line>
          </svg>
          {{ errorMessage || "生成失败，请稍后重试" }}
        </div>
        <button v-if="canRetry" class="retry-btn" @click="$emit('retry')">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            class="retry-icon"
          >
            <polyline points="23 4 23 10 17 10"></polyline>
            <path d="M20.49 15a9 9 0 1 1-2.12-9.36L23 10"></path>
          </svg>
          重新生成
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, watch, nextTick } from "vue";
import { renderAssistantMarkdown, attachImageZoom } from "../services/markdown";

const props = defineProps({
  role: { type: String, default: "assistant" },
  text: { type: String, default: "" },
  rag: { type: Boolean, default: false },
  ragData: { type: Array, default: () => [] },
  error: { type: Boolean, default: false },
  errorMessage: { type: String, default: "" },
  canRetry: { type: Boolean, default: false },
  mcpStatus: { type: String, default: "" }, // '', 'running', 'success', 'error'
  assistantLabel: { type: String, default: "AI" },
  fromName: { type: String, default: "" },
  isStreaming: { type: Boolean, default: false },
});

const avatarText = computed(() =>
  props.role === "user"
    ? "我"
    : String(props.fromName || props.assistantLabel || "AI")
        .substring(0, 2)
        .toUpperCase(),
);

const mcpStatusText = computed(() => {
  if (props.mcpStatus === "running") return "正在调用插件工具...";
  if (props.mcpStatus === "error") return "插件工具调用失败";
  return "";
});

const assistantMarkdownHtml = computed(() => {
  if (props.role !== "assistant" || props.isStreaming) return "";
  return renderAssistantMarkdown(props.text);
});

const markdownEl = ref(null);

onMounted(() => {
  nextTick(() => {
    attachImageZoom(markdownEl.value);
  });
});

watch(assistantMarkdownHtml, () => {
  nextTick(() => attachImageZoom(markdownEl.value));
});
</script>

<style scoped>
.message {
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
  line-height: 1.78;
  letter-spacing: 0.01em;
}

.assistant-markdown {
  white-space: normal;
  color: var(--text-main);
}

.assistant-markdown :deep(*:first-child) {
  margin-top: 0;
}

.assistant-markdown :deep(*:last-child) {
  margin-bottom: 0;
}

.assistant-markdown :deep(p) {
  margin: 0 0 0.58rem;
}

.assistant-markdown :deep(h1),
.assistant-markdown :deep(h2),
.assistant-markdown :deep(h3),
.assistant-markdown :deep(h4) {
  margin: 0.1rem 0 0.55rem;
  line-height: 1.4;
}

.assistant-markdown :deep(ul),
.assistant-markdown :deep(ol) {
  margin: 0.35rem 0 0.58rem;
  padding-left: 1.2rem;
}

.assistant-markdown :deep(li) {
  margin-bottom: 0.24rem;
}

.assistant-markdown :deep(code:not(pre code)) {
  padding: 0.08rem 0.34rem;
  border-radius: 6px;
  font-family: "JetBrains Mono", "Consolas", monospace;
  font-size: 0.86em;
  background: color-mix(in srgb, var(--bg-hover) 62%, transparent);
}

.assistant-markdown :deep(pre.code-block) {
  position: relative;
  margin: 0.72rem 0 0.9rem;
  padding: 2.65rem 0 0;
  border-radius: 16px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 74%, transparent);
  background:
    linear-gradient(180deg, rgba(15, 23, 42, 0.96), rgba(2, 6, 23, 0.98)),
    color-mix(in srgb, var(--bg-hover) 74%, transparent);
  box-shadow:
    0 18px 34px rgba(2, 6, 23, 0.20),
    inset 0 1px 0 rgba(255, 255, 255, 0.05);
  overflow: auto;
  scrollbar-width: thin;
  scrollbar-color: color-mix(in srgb, var(--primary) 50%, transparent) transparent;
}

.assistant-markdown :deep(pre.code-block)::before {
  content: attr(data-language);
  position: absolute;
  top: 0.8rem;
  left: 0.92rem;
  padding: 0.22rem 0.64rem;
  border-radius: 999px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.76);
  font-size: 0.7rem;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  z-index: 1;
}

.assistant-markdown :deep(pre.code-block)::after {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(90deg, rgba(96, 165, 250, 0.12), transparent 26%, transparent 74%, rgba(167, 139, 250, 0.12));
  opacity: 0.9;
}

.assistant-markdown :deep(pre.code-block code) {
  display: block;
  margin: 0;
  padding: 1rem 1rem 1.05rem;
  border-radius: 0;
  background: transparent;
  color: inherit;
  font-family: "JetBrains Mono", "Consolas", monospace;
  font-size: 0.88rem;
  line-height: 1.72;
  white-space: pre;
}

.assistant-markdown :deep(blockquote) {
  margin: 0.42rem 0 0.62rem;
  padding: 0.3rem 0.7rem;
  border-left: 3px solid color-mix(in srgb, var(--primary) 54%, transparent);
  color: var(--text-secondary);
  background: color-mix(in srgb, var(--bg-hover) 48%, transparent);
}

.assistant-markdown :deep(a) {
  color: var(--primary);
  text-decoration: underline;
  text-underline-offset: 2px;
}

.assistant-markdown :deep(table) {
  width: 100%;
  border-collapse: collapse;
  margin: 0.45rem 0 0.6rem;
}

.assistant-markdown :deep(th),
.assistant-markdown :deep(td) {
  border: 1px solid color-mix(in srgb, var(--panel-border) 74%, transparent);
  padding: 0.34rem 0.45rem;
  text-align: left;
}

.assistant-markdown :deep(hr) {
  border: none;
  border-top: 1px solid color-mix(in srgb, var(--panel-border) 70%, transparent);
  margin: 0.65rem 0;
}

.sender-name {
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--text-muted);
}

.rag-sources {
  margin-bottom: 8px;
  font-size: 0.85em;
  background: color-mix(in srgb, var(--bg-hover) 84%, transparent);
  border: 1px solid color-mix(in srgb, var(--panel-border) 76%, transparent);
  padding: 10px;
  border-radius: 10px;
}
.rag-sources summary {
  cursor: pointer;
  color: var(--primary);
  user-select: none;
  font-weight: 620;
}
.rag-sources ul {
  margin: 8px 0 0 0;
  padding-left: 12px;
  list-style: none;
}
.rag-sources li {
  margin-bottom: 4px;
  color: var(--text-secondary);
}
.source-index {
  font-weight: bold;
  margin-right: 4px;
}

.mcp-status {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 0.86em;
  padding: 6px 10px;
  border-radius: 8px;
  margin-bottom: 8px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 74%, transparent);
  background: color-mix(in srgb, var(--bg-hover) 76%, transparent);
}
.mcp-status.running {
  color: var(--primary);
}
.mcp-status.error {
  color: #f87171;
}

.mcp-loading-icon {
  width: 14px;
  height: 14px;
  border: 2px solid var(--primary);
  border-top-color: transparent;
  border-radius: 50%;
  animation: spin 1s linear infinite;
}

.error-container {
  margin-top: 8px;
}
.error-message {
  color: #f87171;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 0.9em;
}
.error-icon {
  width: 16px;
  height: 16px;
}
.retry-btn {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  background: var(--primary);
  color: white;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 0.85em;
  transition: opacity 0.2s, transform 0.18s ease;
}
.retry-btn:hover {
  opacity: 0.9;
  transform: translateY(-1px);
}
.retry-icon {
  width: 14px;
  height: 14px;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
