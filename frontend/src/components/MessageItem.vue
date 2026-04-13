<template>
  <div class="message-wrapper" :class="role">
    <div class="avatar">{{ avatarText }}</div>
    <div class="message-content">
      <div class="rag-tag" v-if="role === 'assistant' && rag">
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

      <div class="message" v-if="text">{{ text }}</div>

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
        <button class="retry-btn" @click="$emit('retry')" v-if="canRetry">
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
import { computed } from "vue";

const props = defineProps({
  role: { type: String, default: "assistant" },
  text: { type: String, default: "" },
  rag: { type: Boolean, default: false },
  ragData: { type: Array, default: () => [] },
  error: { type: Boolean, default: false },
  errorMessage: { type: String, default: "" },
  canRetry: { type: Boolean, default: false },
  mcpStatus: { type: String, default: "" }, // '', 'running', 'success', 'error'
});

const avatarText = computed(() => (props.role === "user" ? "U" : "AI"));

const mcpStatusText = computed(() => {
  if (props.mcpStatus === "running") return "正在调用插件工具...";
  if (props.mcpStatus === "error") return "插件工具调用失败";
  return "";
});
</script>

<style scoped>
.message {
  white-space: pre-wrap;
  word-break: break-word;
}

.rag-sources {
  margin-bottom: 8px;
  font-size: 0.85em;
  background: var(--bg-hover);
  padding: 8px;
  border-radius: 6px;
}
.rag-sources summary {
  cursor: pointer;
  color: var(--primary);
  user-select: none;
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
  font-size: 0.9em;
  padding: 4px 8px;
  border-radius: 4px;
  margin-bottom: 8px;
  background: var(--bg-hover);
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
  transition: opacity 0.2s;
}
.retry-btn:hover {
  opacity: 0.9;
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
