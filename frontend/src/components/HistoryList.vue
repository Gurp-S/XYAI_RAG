<template>
  <div class="history-list-wrapper">
    <div v-if="!history || history.length === 0" class="history-empty">暂无历史记录</div>
    <div
v-for="(h, i) in history" 
         :key="h.conversationId || i" 
         class="history-item"
         :class="{ active: h.conversationId && h.conversationId === activeConversationId }"
         :title="h.title || h.summaryText || '新对话'"
         @click="$emit('select', h)">
      <svg class="history-item-icon" viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" fill="none" stroke-width="2">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
      </svg>
      <div class="history-item-body">
        <span class="history-item-text">{{ h.title || h.summaryText || '对话 ' + (i+1) }}</span>
        <span class="history-item-sub">{{ formatSubText(h) }}</span>
      </div>
    </div>
  </div>
</template>
<script setup>
const props = defineProps({ 
  history: { 
    type: Array, 
    default: () => [] 
  },
  activeConversationId: {
    type: String,
    default: '',
  },
})
defineEmits(['select'])

function formatSubText(item) {
  const summary = String(item?.summaryText || '').trim()
  const summaryText = summary
    ? (summary.length > 20 ? `${summary.slice(0, 20)}...` : summary)
    : ''

  const timestamp = item?.createdAt ? new Date(item.createdAt) : null
  let timeText = ''
  if (timestamp && Number.isFinite(timestamp.getTime())) {
    const mm = String(timestamp.getMonth() + 1).padStart(2, '0')
    const dd = String(timestamp.getDate()).padStart(2, '0')
    const hh = String(timestamp.getHours()).padStart(2, '0')
    const min = String(timestamp.getMinutes()).padStart(2, '0')
    timeText = `${mm}-${dd} ${hh}:${min}`
  }

  if (summaryText && timeText) {
    return `${summaryText} · ${timeText}`
  }

  if (summaryText) {
    return summaryText
  }

  if (timeText) {
    return timeText
  }

  return '点击继续会话'
}
</script>
<style scoped>
.history-list-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
  width: 100%;
  min-height: 160px;
}

.history-empty {
  min-height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.history-item-icon {
  margin-right: 8px;
  opacity: 0.62;
  flex-shrink: 0;
}

.history-item {
  display: flex;
  align-items: flex-start;
}

.history-item-body {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.history-item-text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-item-sub {
  display: block;
  font-size: 11px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-item.active {
  border-color: color-mix(in srgb, var(--primary) 52%, var(--panel-border));
  background: color-mix(in srgb, var(--primary) 14%, transparent);
}

.history-item.active .history-item-icon,
.history-item.active .history-item-text {
  color: var(--text-main);
  opacity: 1;
}
</style>

