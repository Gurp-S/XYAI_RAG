<template>
  <main class="main-content">
    <HeaderBar
      @toggleSidebar="$emit('toggleSidebar')"
      @toggleTheme="toggleTheme"
    />

    <div id="chatBox" class="chat-box" ref="chatBox">
      <div v-if="messages.length === 0" class="empty-state" id="welcomeState">
        <div class="empty-card">
          <div class="empty-emoji"></div>
          <h2 class="empty-title">你好呀！我是 XY-AI</h2>
          <p class="empty-desc">
            我可以帮你检索企业知识库、总结文档、回答业务问题，也可以陪你继续扩展更有趣的智能体体验。试着从左侧上传资料，或直接向我发起一次对话吧。
          </p>
          <div class="empty-hints">
            <span class="hint-pill">支持企业知识库检索</span>
            <span class="hint-pill">支持多轮会话</span>
            <span class="hint-pill">支持上传入库</span>
          </div>
        </div>
      </div>

      <!-- 渲染消息列表，如果是最后一条且正在流式传输且文本为空，则渲染动画包裹层 -->
      <template v-for="(m, idx) in messages" :key="idx">
        <div
          v-if="
            isStreaming &&
            idx === messages.length - 1 &&
            m.role === 'assistant' &&
            m.text === ''
          "
          class="message-wrapper assistant loading"
        >
          <div class="avatar">AI</div>
          <div class="message-content">
            <div class="message typing-loader">
              <span></span>
              <span></span>
              <span></span>
            </div>
          </div>
        </div>
        <MessageItem
          v-else
          :role="m.role"
          :text="m.text"
          :rag="m.rag"
          :ragData="m.ragData"
          :error="m.error"
          :errorMessage="m.errorMessage"
          :canRetry="m.canRetry"
          :mcpStatus="m.mcpStatus"
          @retry="retry(idx)"
        />
      </template>
    </div>

    <FooterInput v-model="input" @send="send" />
  </main>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, watch } from "vue";
import HeaderBar from "./HeaderBar.vue";
import MessageItem from "./MessageItem.vue";
import FooterInput from "./FooterInput.vue";
import { useUiStore } from "../store";

const store = useUiStore();
const chatBox = ref(null);
const messages = ref([]);
const input = ref("");
const isStreaming = ref(false);
let currentAbortController = null;

// 关键：监听 Pinia 中历史消息的变化，将其应用到组件内部 messages
watch(
  () => store.currentMessages,
  (newMsgs) => {
    messages.value = newMsgs || [];
    scrollToBottom();
  },
  { deep: true, immediate: true },
);

function toggleTheme() {
  const isDark = !document.body.classList.contains("dark");
  if (isDark) {
    document.body.classList.add("dark");
    localStorage.setItem("theme", "dark");
  } else {
    document.body.classList.remove("dark");
    localStorage.setItem("theme", "light");
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (chatBox.value) {
      chatBox.value.scrollTop = chatBox.value.scrollHeight;
    }
  });
}

async function send() {
  if (!input.value.trim() || isStreaming.value) return;

  const userText = input.value;
  // 1. 推送用户消息
  messages.value.push({ role: "user", text: userText, rag: false });
  input.value = "";
  scrollToBottom();

  await performChat(userText, messages.value.length);
}

/**
 * 封装核心请求逻辑，支持重试操作
 */
async function performChat(message, userMsgIndex) {
  isStreaming.value = true;

  // 推送或重用助手占位
  let assistantIndex = messages.value.findIndex(
    (m, i) => i >= userMsgIndex && m.role === "assistant",
  );
  if (assistantIndex === -1) {
    messages.value.push({
      role: "assistant",
      text: "",
      rag: false,
      error: false,
      canRetry: false,
      mcpStatus: "",
    });
    assistantIndex = messages.value.length - 1;
  } else {
    // 重置状态
    messages.value[assistantIndex].text = "";
    messages.value[assistantIndex].error = false;
    messages.value[assistantIndex].canRetry = false;
    messages.value[assistantIndex].mcpStatus = "";
  }

  const requestConversationId = store.activeConversationId;

  if (currentAbortController) {
    currentAbortController.abort();
  }
  currentAbortController = new AbortController();

  try {
    const headers = { "Content-Type": "application/json" };
    if (store.currentUser && store.currentUser.id) {
      headers["userId"] = store.currentUser.id;
    }

    const response = await fetch("/ai/chat", {
      method: "POST",
      headers: headers,
      signal: currentAbortController.signal,
      body: JSON.stringify({
        message,
        conversationId: requestConversationId,
        userId: store.currentUser?.id,
      }),
    });

    // S2.3: 统一错误处理，不暴露内部细节
    if (!response.ok) {
      const errorData = await response.json().catch(() => ({}));
      const code = errorData.code || response.status;
      let userFriendlyMsg = "请求出错了，请稍后重试。";

      if (code === 429) userFriendlyMsg = "请求频率过快，请稍息片刻。";
      else if (code === 401) userFriendlyMsg = "登录已失效，请重新登录。";
      else if (code >= 500) userFriendlyMsg = "服务繁忙，系统正在努力修复中。";

      throw new Error(userFriendlyMsg);
    }

    if (!response.body) throw new Error("流式读取失败");

    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let done = false;
    let buffer = "";

    while (!done) {
      if (store.activeConversationId !== requestConversationId) {
        if (currentAbortController) currentAbortController.abort();
        break;
      }

      const { value, done: readerDone } = await reader.read();
      done = readerDone;
      if (value) {
        buffer += decoder.decode(value, { stream: true });
        if (buffer.includes("\n")) {
          const lines = buffer.split("\n");
          buffer = lines.pop();
          for (const line of lines) {
            processSSELine(line, assistantIndex);
          }
        }
      }
    }
    if (buffer) {
      processSSELine(buffer, assistantIndex);
    }
  } catch (error) {
    if (error.name === "AbortError") {
      console.log("--- [DEBUG] Request aborted.");
    } else {
      console.error("Chat Error:", error);
      if (
        messages.value[assistantIndex] &&
        store.activeConversationId === requestConversationId
      ) {
        messages.value[assistantIndex].error = true;
        // 对异常进行脱敏显示
        messages.value[assistantIndex].errorMessage =
          error.message || "网络连接异常，请检查后端服务。";
        messages.value[assistantIndex].canRetry = true;
      }
    }
  } finally {
    isStreaming.value = false;
    scrollToBottom();

    if (store.activeConversationId === requestConversationId) {
      store.currentMessages = [...messages.value];
      saveToCache();
      if (messages.value.length <= 4 && store.currentUser?.id) {
        store.fetchHistory(true);
      }
    }
  }
}

function retry(idx) {
  // 找到该助理消息之前的最后一条用户消息
  let userMsgText = "";
  let userMsgPos = -1;
  for (let i = idx - 1; i >= 0; i--) {
    if (messages.value[i].role === "user") {
      userMsgText = messages.value[i].text;
      userMsgPos = i;
      break;
    }
  }
  if (userMsgText) {
    performChat(userMsgText, userMsgPos);
  }
}

function saveToCache() {
  const convId = store.activeConversationId;
  store.conversationCache[convId] = store.currentMessages;
  localStorage.setItem(
    "conversationCache",
    JSON.stringify(store.conversationCache),
  );
}

// 辅助函数：处理单行 SSE 数据并更新界面
function processSSELine(line, assistantIndex) {
  let content = "";
  if (!line.trim()) return;

  if (line.startsWith("data:")) {
    content = line.replace("data:", "").trim();
  } else if (line.trim() && !line.startsWith(":")) {
    content = line;
  }

  if (content === "[DONE]") return;

  // 尝试解析 JSON 控制指令 (RAG/MCP)
  try {
    const json = JSON.parse(content);
    if (json.type === "rag_hits") {
      messages.value[assistantIndex].rag = true;
      messages.value[assistantIndex].ragData = json.data; // 包含命中摘要与来源
      return;
    }
    if (json.type === "mcp_status") {
      messages.value[assistantIndex].mcpStatus = json.status; // running, success, error
      return;
    }
    if (json.content) {
      content = json.content;
    }
  } catch (e) {
    // 按普通文本处理
  }

  messages.value[assistantIndex].text += content;
  scrollToBottom();
}

onMounted(() => {
  const savedTheme = localStorage.getItem("theme");
  if (savedTheme === "dark") document.body.classList.add("dark");
});

onUnmounted(() => {
  if (currentAbortController) {
    currentAbortController.abort();
  }
});
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
.typing-loader span:nth-child(1) {
  animation-delay: -0.32s;
}
.typing-loader span:nth-child(2) {
  animation-delay: -0.16s;
}

@keyframes typing-dot {
  0%,
  80%,
  100% {
    transform: scale(0);
    opacity: 0.3;
  }
  40% {
    transform: scale(1);
    opacity: 1;
  }
}
</style>
