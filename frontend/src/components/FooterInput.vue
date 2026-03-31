<template>
  <section class="input-area">
    <div class="input-wrapper">
      <textarea :value="modelValue" @input="$emit('update:modelValue', $event.target.value)" @keydown.enter.exact.prevent="$emit('send')" placeholder="输入你的问题，按 Enter 发送，Shift+Enter 换行"></textarea>
      <button class="btn-send" @click="$emit('send')">发送</button>
    </div>
  </section>
</template>
<script setup>
import { defineProps } from 'vue'
const props = defineProps({ modelValue: { type: String, default: '' } })
</script>
<style scoped>
.input-area{padding:12px}
</style>

