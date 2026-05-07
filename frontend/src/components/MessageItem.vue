<template>
  <div class="message-wrapper" :class="role">
    <div class="avatar">
      <template v-if="avatarUrl">
        <img :src="avatarUrl" alt="avatar" class="avatar-img" />
      </template>
      <template v-else>
        {{ avatarText }}
      </template>
    </div>
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
        v-if="text && role === 'assistant'"
        class="message assistant-markdown"
        ref="markdownEl"
        v-html="assistantMarkdownHtml"
      ></div>
      <div v-else-if="text" class="message">{{ text }}</div>

      <!-- 文件分享卡片 -->
      <div v-if="file" class="file-card" :class="{ received: file.status === 'pending' }">
        <div class="file-card-icon">
          <svg viewBox="0 0 24 24" width="24" height="24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path>
            <polyline points="14 2 14 8 20 8"></polyline>
            <line x1="16" y1="13" x2="8" y2="13"></line>
            <line x1="16" y1="17" x2="8" y2="17"></line>
          </svg>
        </div>
        <div class="file-card-info">
          <div class="file-card-name">{{ file.name || '文件' }}</div>
          <div class="file-card-meta">
            {{ file.size ? formatFileSize(file.size) : '' }}
            <span v-if="file.status === 'pending'" class="file-card-badge">待接收</span>
            <span v-else-if="file.status === 'accepted'" class="file-card-badge accepted">已接收</span>
          </div>
        </div>
        <button v-if="file.status === 'pending'" class="file-card-accept" type="button" @click="$emit('acceptFile', file)">
          <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
            <polyline points="20 6 9 17 4 12"></polyline>
          </svg>
          接收
        </button>
      </div>

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
import { useUiStore } from '../store'
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
  file: { type: Object, default: null },
});

const emit = defineEmits(["retry", "edit", "acceptFile"]);

function formatFileSize(bytes) {
  if (!bytes || bytes === 0) return ''
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0
  let size = bytes
  while (size >= 1024 && i < units.length - 1) { size /= 1024; i++ }
  return size.toFixed(i > 0 ? 1 : 0) + ' ' + units[i]
}

const avatarText = computed(() =>
  props.role === "user"
    ? "我"
    : String(props.fromName || props.assistantLabel || "AI")
        .substring(0, 2)
        .toUpperCase(),
);

const store = useUiStore()
const avatarUrl = computed(() => {
  if (props.role === 'user') return store.currentUser?.avatar || ''
  return ''
})

const mcpStatusText = computed(() => {
  if (props.mcpStatus === "running") return "正在调用插件工具...";
  if (props.mcpStatus === "error") return "插件工具调用失败";
  return "";
});

const assistantMarkdownHtml = computed(() => {
  if (props.role !== "assistant") return "";
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
  font-size: 0.98rem;
  line-height: 1.82;
  background: linear-gradient(
    180deg,
    color-mix(in srgb, var(--bot-msg) 96%, var(--surface-solid)),
    color-mix(in srgb, var(--bot-msg) 90%, var(--surface-solid))
  );
  border-color: color-mix(in srgb, var(--panel-border) 86%, transparent);
  box-shadow: 0 12px 24px rgba(15, 23, 42, 0.08);
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
  background: color-mix(in srgb, var(--primary) 10%, var(--surface-solid));
  border: 1px solid color-mix(in srgb, var(--panel-border) 68%, transparent);
  color: var(--text-main);
}

.assistant-markdown :deep(pre.code-block) {
  position: relative;
  margin: 0.72rem 0 0.9rem;
  padding: 2.65rem 0 0;
  border-radius: 16px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 74%, transparent);
  background:
    linear-gradient(180deg, rgba(10, 15, 28, 0.98), rgba(5, 10, 20, 0.98)),
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
  opacity: 0.34;
}

.assistant-markdown :deep(pre.code-block code) {
  display: block;
  margin: 0;
  padding: 1rem 1rem 1.05rem;
  border-radius: 0;
  background: transparent;
  color: inherit;
  font-family: "JetBrains Mono", "Consolas", monospace;
  font-size: 0.92rem;
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

.avatar-img {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  object-fit: cover;
  display: block;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

/* ─── File Card ─── */
.file-card {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  padding: 10px 14px;
  border-radius: 12px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 50%, transparent);
  background: color-mix(in srgb, var(--surface-soft) 80%, transparent);
}
.file-card.received {
  border-color: color-mix(in srgb, var(--primary) 20%, transparent);
  background: color-mix(in srgb, var(--primary) 4%, transparent);
}
.file-card-icon {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  background: color-mix(in srgb, var(--primary) 10%, transparent);
  color: var(--primary);
  flex-shrink: 0;
}
.file-card-info {
  flex: 1;
  min-width: 0;
}
.file-card-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-main);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.file-card-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  font-weight: 400;
  color: var(--text-muted);
  margin-top: 2px;
}
.file-card-badge {
  font-size: 10px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
  background: color-mix(in srgb, #f59e0b 14%, transparent);
  color: #d97706;
}
.file-card-badge.accepted {
  background: color-mix(in srgb, #22c55e 12%, transparent);
  color: #16a34a;
}
.file-card-accept {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  border-radius: 8px;
  border: none;
  background: var(--primary);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  flex-shrink: 0;
  transition: background-color 0.12s;
}
.file-card-accept:hover {
  background: var(--primary-hover);
}
</style>
