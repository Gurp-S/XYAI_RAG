<template>
  <div class="message-wrapper" :class="role">
    <div class="avatar">{{ avatarText }}</div>
    <div class="message-content">
      <div class="rag-tag" v-if="rag">RAG</div>
      <div class="message" v-html="text"></div>
    </div>
  </div>
</template>
<script setup>
import { computed } from 'vue'
const props = defineProps({ role: { type: String, default: 'assistant' }, text: { type: String }, rag: { type: Boolean, default: false } })
const avatarText = computed(() => props.role === 'user' ? 'AU' : 'XY')
</script>
<style scoped>
.message-wrapper{display:flex;gap:12px}
</style>

