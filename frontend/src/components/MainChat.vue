<template>
  <main class="main-content">
    <HeaderBar @toggleTheme="toggleTheme" @toggleSidebar="$emit('toggleSidebar')" />

    <section class="chat-box" ref="chatBox">
      <div v-if="messages.length===0" class="empty-state">
        <div class="empty-card">
          <div class="empty-emoji">🐱</div>
          <h3 class="empty-title">你好呀！我是 XY-AI</h3>
          <p class="empty-desc">试着上传资料或直接向我发起一次对话吧。</p>
        </div>
      </div>

      <MessageItem v-for="(m, idx) in messages" :key="idx" :role="m.role" :text="m.text" :rag="m.rag" />
    </section>

    <FooterInput v-model="input" @send="send" />
  </main>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import HeaderBar from './HeaderBar.vue'
import MessageItem from './MessageItem.vue'
import FooterInput from './FooterInput.vue'

const chatBox = ref(null)
const messages = ref([])
const input = ref('')
const isStreaming = ref(false)
let currentAbort = null

function scrollToBottom() {
  if (!chatBox.value) return
  requestAnimationFrame(() => {
    chatBox.value.scrollTop = chatBox.value.scrollHeight
  })
}

function safeHtml(text) {
  return String(text || '').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/\n/g, '<br/>')
}

async function send() {
  if (!input.value.trim()) return
  if (isStreaming.value && currentAbort) {
    currentAbort.abort()
    isStreaming.value = false
    currentAbort = null
    return
  }

  const text = input.value
  messages.value.push({ role: 'user', text: safeHtml(text), rag: false })
  input.value = ''
  scrollToBottom()

  messages.value.push({ role: 'assistant', text: '...', rag: Math.random() > 0.6 })
  const assistantIndex = messages.value.length - 1

  const controller = new AbortController()
  currentAbort = controller
  isStreaming.value = true

  try {
    const resp = await fetch('/ai/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ message: text }),
      signal: controller.signal
    })

    if (!resp.ok) {
      messages.value[assistantIndex].text = `请求失败: HTTP ${resp.status}`
      isStreaming.value = false
      currentAbort = null
      scrollToBottom()
      return
    }

    if (!resp.body) {
      const t = await resp.text()
      messages.value[assistantIndex].text = safeHtml(t)
      isStreaming.value = false
      currentAbort = null
      scrollToBottom()
      return
    }

    const reader = resp.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buf = ''
    let output = ''
    let renderPending = false

    const flushRender = () => {
      if (renderPending) return
      renderPending = true
      requestAnimationFrame(() => {
        messages.value[assistantIndex].text = safeHtml(output)
        scrollToBottom()
        renderPending = false
      })
    }

    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      let idx
      while ((idx = buf.indexOf('\n')) !== -1) {
        const line = buf.slice(0, idx).trim()
        buf = buf.slice(idx + 1)
        if (!line) continue
        const part = line.startsWith('data:') ? line.substring(5) : line
        output += part
      }
      flushRender()
    }

    if (buf.length) {
      output += buf
      flushRender()
    }

    messages.value[assistantIndex].text = safeHtml(output)
    isStreaming.value = false
    currentAbort = null
    scrollToBottom()
  } catch (err) {
    if (err.name === 'AbortError') {
      messages.value.push({ role: 'system', text: '已取消流式响应' })
    } else {
      messages.value[assistantIndex].text = `请求失败: ${err.message}`
    }
    isStreaming.value = false
    currentAbort = null
    scrollToBottom()
  }
}

onMounted(() => {})

function toggleTheme() {
  document.body.classList.toggle('dark')
}
</script>

<style scoped>
.main-content{display:flex;flex-direction:column;height:100%}
.chat-box{flex:1;overflow:auto}
</style>

