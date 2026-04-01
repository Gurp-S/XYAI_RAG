<template>
  <div class="message-wrapper" :class="role">
    <div class="avatar">{{ avatarText }}</div>
    <div class="message-content">
      <div class="rag-tag" v-if="role === 'assistant' && rag">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"></circle><line x1="21" y1="21" x2="16.65" y2="16.65"></line></svg> 
          已检索企业知识库 (共命中 3 段相关切片)
      </div>
      <div class="message" v-html="text"></div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  role: { type: String, default: 'assistant' },
  text: { type: String },
  rag: { type: Boolean, default: false }
})

const avatarText = computed(() => props.role === 'user' ? 'U' : 'AI')
</script>

<style scoped>
/* Scoped styles are handled globally via styles.css */
</style>
