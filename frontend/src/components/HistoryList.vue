<template>
  <div class="history-container">
    <div class="history-label">历史对话</div>
    <div v-for="(h, i) in history" :key="i" class="history-item">{{ h.title || '会话 ' + (i+1) }}</div>
  </div>
</template>
<script setup>
const props = defineProps({ history: { type: Array, default: () => [] } })
</script>
<style scoped>
.history-container{padding:12px}
</style>

