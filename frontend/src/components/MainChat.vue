<template>
  <main class="main-content">
    <HeaderBar @toggleSidebar="$emit('toggleSidebar')" @toggleTheme="toggleTheme" />

    <div id="chatBox" class="chat-box" ref="chatBox">
        <div v-if="messages.length === 0" class="empty-state" id="welcomeState">
            <div class="empty-card">
                <div class="empty-emoji"></div>
                <h2 class="empty-title">你好呀！我是 XY-AI</h2>
                <p class="empty-desc">我可以帮你检索企业知识库、总结文档、回答业务问题，也可以陪你继续扩展更有趣的智能体体验。试着从左侧上传资料，或直接向我发起一次对话吧。</p>
                <div class="empty-hints">
                    <span class="hint-pill">支持企业知识库检索</span>
                    <span class="hint-pill">支持多轮会话</span>
                    <span class="hint-pill">支持上传入库</span>
                </div>
            </div>
        </div>

        <MessageItem v-for="(m, idx) in messages" :key="idx" :role="m.role" :text="m.text" :rag="m.rag" />
    </div>

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

function toggleTheme() {
    const isDark = !document.body.classList.contains('dark')
    if (isDark) {
        document.body.classList.add('dark')
        localStorage.setItem('theme', 'dark')
    } else {
        document.body.classList.remove('dark')
        localStorage.setItem('theme', 'light')
    }
}

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

  messages.value.push({ role: 'assistant', text: '思考中...', rag: Math.random() > 0.6 })
  const assistantIndex = messages.value.length - 1
  
  setTimeout(() => {
      messages.value[assistantIndex].text = safeHtml('您发送的内容是：' + text)
      scrollToBottom()
  }, 500)
}
</script>

<style scoped>
</style>
