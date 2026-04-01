<template>
  <footer class="input-area">
    <div class="input-wrapper">
        <label class="sr-only" for="message">消息输入</label>
        <textarea id="message" rows="1"
            placeholder="输入你想咨询的问题，或要求查询企业知识库... (按 Enter 发送，Shift+Enter 换行)"
            :value="modelValue" 
            @input="onInput"
            @keydown.enter.prevent="handleEnter"
            ref="textareaRef"
            ></textarea>
        <button id="send" class="btn-send" title="发送消息" @click="$emit('send')">
            <svg viewBox="0 0 24 24">
                <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" />
            </svg>
        </button>
    </div>
  </footer>
</template>

<script setup>
import { ref } from 'vue'

const props = defineProps(['modelValue'])
const emit = defineEmits(['update:modelValue', 'send'])
const textareaRef = ref(null)

function onInput(e) {
  emit('update:modelValue', e.target.value)
  e.target.style.height = 'auto'
  e.target.style.height = (e.target.scrollHeight) + 'px'
  if (e.target.value === '') e.target.style.height = 'auto'
}

function handleEnter(e) {
  if (!e.shiftKey) {
    emit('send')
  }
}
</script>

<style scoped>
/* Managed in styles.css */
</style>
