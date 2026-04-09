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
import { ref, watch, nextTick } from 'vue'

const props = defineProps(['modelValue'])
const emit = defineEmits(['update:modelValue', 'send'])
const textareaRef = ref(null)

// 深度重置高度逻辑
function adjustHeight() {
  const el = textareaRef.value
  if (!el) return
  
  el.style.height = 'auto'
  if (el.value) {
    // 限制最高高度防止遮挡聊天区域
    const maxHeight = 200
    const targetHeight = Math.min(el.scrollHeight, maxHeight)
    el.style.height = targetHeight + 'px'
    el.style.overflowY = el.scrollHeight > maxHeight ? 'auto' : 'hidden'
  } else {
    el.style.overflowY = 'hidden'
  }
}

// 监听外部对 modelValue 的清空（例如发送后清空）
watch(() => props.modelValue, (newVal) => {
  if (newVal === '') {
    nextTick(() => {
      adjustHeight()
    })
  }
})

function onInput(e) {
  emit('update:modelValue', e.target.value)
  adjustHeight()
}

function handleEnter(e) {
  if (!e.shiftKey) {
    emit('send')
    // 发送后立即手动重置一次，防止由于 nextTick 导致的视觉延迟
    setTimeout(() => adjustHeight(), 0)
  }
}
</script>

<style scoped>
/* Managed in styles.css */
</style>
