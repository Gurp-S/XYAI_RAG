<template>
  <div class="history-list-wrapper">
    <div class="history-label">历史对话</div>
    <div v-if="!history || history.length === 0" class="history-empty">暂无历史记录</div>
    <div v-for="(h, i) in history" 
         :key="h.conversationId || i" 
         class="history-item"
         :title="h.title || h.summaryText || '新对话'"
         @click="$emit('select', h)">
      <svg viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" fill="none" stroke-width="2" style="margin-right:8px;opacity:0.6;">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
      </svg>
      <span>{{ h.title || h.summaryText || '对话 ' + (i+1) }}</span>
    </div>
  </div>
</template>
<script setup>
const props = defineProps({ 
  history: { 
    type: Array, 
    default: () => [] 
  } 
})
defineEmits(['select'])
</script>
<style scoped>
.history-list-wrapper {
  display: flex;
  flex-direction: column;
  width: 100%;
}
.history-label {
    padding: 0.5rem 1.5rem;
    font-size: 0.75rem;
    color: #94a3b8;
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.5px;
}
.history-empty {
  padding: 10px 1.5rem;
  font-size: 0.85rem;
  color: var(--text-muted);
  opacity: 0.7;
}
.history-item {
    padding: 0.85rem 1.5rem;
    display: flex;
    align-items: center;
    cursor: pointer;
    transition: all 0.22s ease;
    font-size: 0.9rem;
    color: var(--text-main);
    border-left: 3px solid transparent;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
}
body.dark .history-item {
    color: #cbd5e1;
}
.history-item:hover {
    background: var(--sidebar-hover);
    transform: translateX(3px);
}
</style>

