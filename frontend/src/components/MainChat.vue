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

        <!-- 仅渲染消息列表 -->
        <MessageItem v-for="(m, idx) in messages" :key="idx" :role="m.role" :text="m.text" :rag="m.rag" />
        
        <!-- 加载动画占位：仅在该消息正在流式传输且内容尚为空时显示 -->
        <div v-if="isStreaming && messages.length > 0 && messages[messages.length-1].role === 'assistant' && messages[messages.length-1].text === ''" class="message-wrapper assistant loading">
            <div class="avatar">AI</div>
            <div class="message-content">
                <div class="message typing-loader">
                    <span></span>
                    <span></span>
                    <span></span>
                </div>
            </div>
        </div>
    </div>

    <FooterInput v-model="input" @send="send" />
  </main>
</template>

<script setup>
import { ref, onMounted, nextTick, watch } from 'vue'
import HeaderBar from './HeaderBar.vue'
import MessageItem from './MessageItem.vue'
import FooterInput from './FooterInput.vue'
import { useUiStore } from '../store'

const store = useUiStore()
const chatBox = ref(null)
const messages = ref([])
const input = ref('')
const isStreaming = ref(false)

// 关键：监听 Pinia 中历史消息的变化，将其应用到组件内部 messages
watch(() => store.currentMessages, (newMsgs) => {
  messages.value = [...newMsgs]
  scrollToBottom()
}, { deep: true })

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
  nextTick(() => {
    if (chatBox.value) {
      chatBox.value.scrollTop = chatBox.value.scrollHeight
    }
  })
}

async function send() {
  if (!input.value.trim() || isStreaming.value) return

  const userText = input.value
  // 1. 推送用户消息
  messages.value.push({ role: 'user', text: userText, rag: false })
  input.value = ''
  scrollToBottom()

  isStreaming.value = true
  // 2. 推送助手占位，此时其 text 为空，HTML 同步渲染逻辑显示 loading 动画
  messages.value.push({ role: 'assistant', text: '', rag: false })
  const assistantIndex = messages.value.length - 1

  try {
    const response = await fetch('/ai/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ 
        message: userText, 
        conversationId: store.activeConversationId,
        userId: store.currentUser ? store.currentUser.id : null 
      })
    })

    if (!response.body) throw new Error('ReadableStream not supported')

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let done = false

    while (!done) {
      const { value, done: readerDone } = await reader.read()
      done = readerDone
      if (value) {
        const chunk = decoder.decode(value, { stream: true })
        // 简单处理 Flux 返回的 SSE 格式或纯文本块
        const lines = chunk.split('\n')
        for (const line of lines) {
            let content = ''
            if (line.startsWith('data:')) {
                content = line.replace('data:', '').trim()
            } else if (line.trim() && !line.startsWith(':')) {
                content = line
            }
            if (content) {
                // 第一个字符到来，loading 动画由于 text 不再为空而自动消失
                messages.value[assistantIndex].text += content
                scrollToBottom()
            }
        }
      }
    }
  } catch (error) {
    console.error('Chat Error:', error)
    messages.value[assistantIndex].text = '消息发送失败，请检查后端服务是否启动。'
  } finally {
    isStreaming.value = false
    scrollToBottom()
  }
}

onMounted(() => {
  const savedTheme = localStorage.getItem('theme')
  if (savedTheme === 'dark') document.body.classList.add('dark')
})
</script>

<style scoped>
.typing-loader {
    padding: 12px 16px !important;
    display: flex !important;
    gap: 4px !important;
    align-items: center !important;
    min-height: 40px;
    background: var(--bot-msg);
    border: 1px solid var(--bot-border);
    border-radius: 12px;
}
.typing-loader span {
    width: 6px;
    height: 6px;
    background-color: var(--primary);
    border-radius: 50%;
    display: inline-block;
    animation: typing-dot 1.4s infinite ease-in-out both;
}
.typing-loader span:nth-child(1) { animation-delay: -0.32s; }
.typing-loader span:nth-child(2) { animation-delay: -0.16s; }

@keyframes typing-dot {
    0%, 80%, 100% { transform: scale(0); opacity: 0.3; }
    40% { transform: scale(1); opacity: 1; }
}
</style>