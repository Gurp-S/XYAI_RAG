<template>
  <div class="message-wrapper" :class="role">
    <div class="avatar">{{ avatarText }}</div>
    <div class="message-content">
      <div class="rag-tag" v-if="role === 'assistant' && rag">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg> 
          已检索企业知识库 (共命中 3 段相关切片)
      </div>
      <div class="message">{{ text }}</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  role: { type: String, default: 'assistant' },
  text: { type: String, default: '' },
  rag: { type: Boolean, default: false }
})

const avatarText = computed(() => props.role === 'user' ? 'U' : 'AI')
</script>

<style scoped>
.message {
  white-space: pre-wrap; /* 关键：保留换行符并自动换行，避免昂贵的 v-html 正则替换 */
  word-break: break-word;
}
</style>
