<template>
  <div v-if="files.length" class="file-attachments">
    <div
      v-for="(f, i) in files"
      :key="i"
      class="file-tag"
      :class="{ 'tag-parsing': f._parsing, 'tag-error': f._error }"
    >
      <!-- 文件类型图标（SVG，与设计系统统一） -->
      <svg class="tag-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
        <polyline points="14 2 14 8 20 8" />
      </svg>

      <!-- 文件名 + 元信息 -->
      <div class="tag-body">
  <span class="tag-name">{{ f.name }}</span>
  <span class="tag-meta">
    <template v-if="f._parsing">
      <span class="meta-spin" /> 解析中…
    </template>
    <template v-else-if="f._error">
      <span class="meta-err">{{ f._error }}</span>
    </template>
    <template v-else>
      <span class="meta-ok">已解析</span> · {{ formatSize(f.size) }}
    </template>
  </span>
</div>

      <!-- 移除按钮 -->
      <button class="tag-remove" @click="emit('remove', i)">
        <svg viewBox="0 0 24 24" width="12" height="12" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
          <line x1="18" y1="6" x2="6" y2="18" />
          <line x1="6" y1="6" x2="18" y2="18" />
        </svg>
      </button>
    </div>
  </div>
</template>

<script setup>
defineProps({ files: { type: Array, default: () => [] } })
const emit = defineEmits(['remove'])

function formatSize(bytes) {
  if (!bytes || bytes < 0) return ''
  if (bytes < 1024) return bytes + 'B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + 'KB'
  return (bytes / (1024 * 1024)).toFixed(1) + 'MB'
}
</script>

<style scoped>
.file-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  padding: 8px 4% 4px;
  background: transparent;
}

/* 单个附件胶囊标签 */
.file-tag {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px 6px 8px;
  border-radius: 24px;
  background: var(--glass-soft);
  border: 1px solid var(--panel-border);
  box-shadow: 0 2px 8px rgba(38, 68, 126, 0.06);
  color: var(--text-main);
  font-size: 13px;
  transition: all 0.2s var(--motion-ease);
  cursor: default;
  max-width: 280px;
  animation: tagPopIn 0.2s cubic-bezier(0.22, 1, 0.36, 1) both;
}

.file-tag:hover {
  background: var(--hover-bg);
  border-color: color-mix(in srgb, var(--primary) 30%, var(--panel-border));
  box-shadow: 0 4px 12px rgba(38, 68, 126, 0.1);
  transform: translateY(-1px);
}

/* 解析中状态 */
.tag-parsing {
  border-color: rgba(124, 58, 237, 0.3);
  background: rgba(245, 243, 255, 0.85);
}

/* 解析失败状态 */
.tag-error {
  border-color: rgba(239, 68, 68, 0.3);
  background: rgba(254, 242, 242, 0.85);
}

/* 文件图标 */
.tag-icon {
  width: 18px;
  height: 18px;
  flex-shrink: 0;
  color: var(--text-muted);
  opacity: 0.8;
}

.meta-ok {
  color: #10b981;
  font-weight: 500;
}

/* 文件名 + 元信息容器 */
.tag-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.tag-name {
  font-weight: 600;
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  color: var(--text-main);
}

.tag-meta {
  font-size: 10px;
  color: var(--text-muted);
  display: flex;
  align-items: center;
  gap: 4px;
}

/* 解析中旋转指示器 */
.meta-spin {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: 2px solid currentColor;
  border-top-color: transparent;
  animation: spin 0.8s linear infinite;
}

.meta-err {
  color: #ef4444;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 120px;
}

/* 移除按钮 */
.tag-remove {
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: none;
  background: transparent;
  color: var(--text-muted);
  display: grid;
  place-items: center;
  cursor: pointer;
  flex-shrink: 0;
  transition: all 0.15s;
}

.tag-remove:hover {
  background: #ef4444;
  color: #fff;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

@keyframes tagPopIn {
  from {
    opacity: 0;
    transform: scale(0.9) translateY(4px);
  }
  to {
    opacity: 1;
    transform: scale(1) translateY(0);
  }
}

/* 移动端适配 */
@media (max-width: 980px) {
  .file-attachments {
    padding: 6px 3% 2px;
    gap: 6px;
  }

  .file-tag {
    max-width: 240px;
    padding: 5px 10px 5px 6px;
  }
}
</style>