<template>
  <main class="main-content chat-home-shell" :class="{ streaming: isStreaming }">
    <HeaderBar
      @toggle-sidebar="$emit('toggleSidebar')"
    />

    <div v-if="!isAiChat" class="chat-context-bar">
      <span class="context-tag">{{ store.chatMode === 'group' ? '群聊' : '用户聊天' }}</span>
      <strong class="context-name">{{ chatTargetName }}</strong>
    </div>

    <div id="chatBox" ref="chatBox" class="chat-box">
      <MessageItem
        v-if="isAiChat"
        role="assistant"
        :text="aiGreetingText"
        :assistant-label="assistantLabel"
      />

      <!-- 渲染消息列表，如果是最后一条且正在流式传输且文本为空，则渲染动画包裹层 -->
      <template v-for="(m, idx) in messages" :key="buildMessageKey(m, idx)">
        <div
          v-if="
            isAiChat &&
            isStreaming &&
            idx === messages.length - 1 &&
            m.role === 'assistant' &&
            m.text === ''
          "
          class="message-wrapper assistant loading"
        >
          <div class="avatar">{{ assistantLabel }}</div>
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
          :rag-data="m.ragData"
          :error="m.error"
          :error-message="m.errorMessage"
          :can-retry="m.canRetry"
          :mcp-status="m.mcpStatus"
          :assistant-label="assistantLabel"
          :from-name="m.fromName"
          :is-streaming="isAiChat && isStreaming && idx === messages.length - 1 && m.role === 'assistant'"
          @retry="handleRetry(idx)"
        />
      </template>
    </div>

    <FooterInput v-model="input" @send="send" />
  </main>
</template>

<script setup>
import { computed, ref, onMounted, onUnmounted, nextTick, watch } from "vue";
import HeaderBar from "./HeaderBar.vue";
import MessageItem from "./MessageItem.vue";
import FooterInput from "./FooterInput.vue";
import { useUiStore } from "../store";
import { authFetch, safeReadJson } from "../services/api";

const store = useUiStore();
const chatBox = ref(null);
const messages = ref([]);
const input = ref("");
const isStreaming = ref(false);
let currentAbortController = null;
let scrollRafId = 0;
let streamFlushRafId = 0;
let streamFlushTimer = 0;
let pendingChunkText = "";
let pendingChunkAssistantIndex = -1;
let lastStreamFlushTs = 0;
let contactPollingTimer = null;
let isContactSyncing = false;
let greetingTimer = null;
const greetingClockTick = ref(Date.now());
const STREAM_FLUSH_MIN_INTERVAL = 24;
const STREAM_FLUSH_FORCE_CHARS = 320;

const isAiChat = computed(() => store.chatMode === "ai");
const chatTargetName = computed(() =>
  store.chatTarget?.name || (store.chatMode === "group" ? "当前群聊" : "当前用户"),
);
const contactModeReady = computed(
  () =>
    !isAiChat.value &&
    !!store.currentUser?.id &&
    !!store.chatTarget?.id,
);

const assistantLabel = computed(() => {
  if (store.chatMode === "ai") return "AI";
  const source = chatTargetName.value || (store.chatMode === "group" ? "群" : "友");
  return String(source).substring(0, 2).toUpperCase();
});

const aiGreetingText = computed(() => {
  const hour = new Date(greetingClockTick.value).getHours();
  let phaseGreeting = "你好";

  if (hour >= 5 && hour < 11) {
    phaseGreeting = "早上好";
  } else if (hour >= 11 && hour < 18) {
    phaseGreeting = "下午好";
  } else if (hour >= 18 && hour < 23) {
    phaseGreeting = "晚上好";
  } else {
    phaseGreeting = "夜深了";
  }

  return `${phaseGreeting}，我是 XY-AI（策略搭子模式）。你负责提目标，我负责拆步骤、找依据、给可执行答案。`;
});

function buildMessageKey(message, index) {
  const baseRole = message?.role || "message";
  const preferredId =
    message?.id || message?.messageId || message?.uuid || message?.createdAt || "";
  return `${baseRole}-${preferredId || index}`;
}

// 关键：监听 Pinia 中历史消息的变化，将其应用到组件内部 messages
watch(
  () => store.currentMessages,
  (newMsgs) => {
    messages.value = newMsgs || [];
    scrollToBottom();
  },
  { immediate: true },
);

watch(
  () => [store.chatMode, store.chatTarget?.id, store.currentUser?.id, store.activeConversationId],
  () => {
    if (contactModeReady.value) {
      startContactPolling();
      return;
    }
    stopContactPolling();
  },
  { immediate: true },
);

function scrollToBottom() {
  if (scrollRafId) return;
  scrollRafId = requestAnimationFrame(() => {
    scrollRafId = 0;
    nextTick(() => {
      if (chatBox.value) {
        chatBox.value.scrollTop = chatBox.value.scrollHeight;
      }
    });
  });
}

function flushPendingChunk() {
  if (!pendingChunkText || pendingChunkAssistantIndex < 0) return;

  const target = messages.value[pendingChunkAssistantIndex];
  if (target && target.role === "assistant") {
    target.text += pendingChunkText;
  }

  pendingChunkText = "";
  pendingChunkAssistantIndex = -1;
  lastStreamFlushTs =
    typeof performance !== "undefined" ? performance.now() : Date.now();
  scrollToBottom();
}

function scheduleChunkFlush() {
  if (!pendingChunkText) return;

  const now =
    typeof performance !== "undefined" ? performance.now() : Date.now();
  const elapsed = now - lastStreamFlushTs;
  const shouldForceFlush = pendingChunkText.length >= STREAM_FLUSH_FORCE_CHARS;

  if (shouldForceFlush || elapsed >= STREAM_FLUSH_MIN_INTERVAL) {
    if (streamFlushTimer) {
      clearTimeout(streamFlushTimer);
      streamFlushTimer = 0;
    }
    if (streamFlushRafId) return;
    streamFlushRafId = requestAnimationFrame(() => {
      streamFlushRafId = 0;
      flushPendingChunk();
    });
    return;
  }

  if (streamFlushTimer) return;
  streamFlushTimer = setTimeout(() => {
    streamFlushTimer = 0;
    if (streamFlushRafId) return;
    streamFlushRafId = requestAnimationFrame(() => {
      streamFlushRafId = 0;
      flushPendingChunk();
    });
  }, STREAM_FLUSH_MIN_INTERVAL - elapsed);
}

async function send() {
  if (!input.value.trim() || isStreaming.value) return;

  const userText = input.value.trim();
  input.value = "";

  if (!isAiChat.value) {
    messages.value.push({
      role: "user",
      text: userText,
      fromName: store.userDisplayName,
      rag: false,
      optimistic: true,
    });
    store.updateCurrentMessages(messages.value);
    scrollToBottom();
    await sendContactMessage(userText);
    return;
  }

  // AI 模式：本地先推送用户消息，再进入 SSE 流式回复。
  store.ensureAiConversationEntry(store.activeConversationId, userText);
  messages.value.push({ role: "user", text: userText, rag: false });
  scrollToBottom();

  store.setConversationPreviewTitle(store.activeConversationId, userText);
  await performAiChat(userText, messages.value.length);
}

function stopContactPolling() {
  if (contactPollingTimer) {
    clearInterval(contactPollingTimer);
    contactPollingTimer = null;
  }
}

function startContactPolling() {
  stopContactPolling();
  loadContactMessages();
  contactPollingTimer = setInterval(() => {
    if (
      typeof document !== "undefined" &&
      document.visibilityState === "hidden"
    ) {
      return;
    }
    loadContactMessages();
  }, 1800);
}

function areContactMessagesEqual(prev, next) {
  if (!Array.isArray(prev) || !Array.isArray(next)) return false;
  if (prev.length !== next.length) return false;

  for (let i = 0; i < prev.length; i += 1) {
    const a = prev[i] || {};
    const b = next[i] || {};
    if (
      a.role !== b.role ||
      a.text !== b.text ||
      a.fromName !== b.fromName ||
      a.createdAt !== b.createdAt
    ) {
      return false;
    }
  }

  return true;
}

function mapContactMessage(item) {
  const senderId = String(item?.senderId ?? "");
  const currentUserId = String(store.currentUser?.id ?? "");
  const isMine = senderId === currentUserId;
  const fallbackName = isMine ? store.userDisplayName : store.chatTarget?.name || "对方";

  return {
    role: isMine ? "user" : "assistant",
    text: String(item?.content ?? ""),
    fromName: String(item?.senderName || fallbackName),
    createdAt: item?.timestamp,
  };
}

async function loadContactMessages() {
  if (!contactModeReady.value || isContactSyncing) return;

  isContactSyncing = true;
  try {
    const query = new URLSearchParams({
      targetType: String(store.chatMode),
      targetId: String(store.chatTarget.id),
    });

    const response = await authFetch(`/user-chat/messages?${query.toString()}`, {
      method: 'POST'
    });
    const result = await safeReadJson(response);
    if (!response.ok || !result || result.code !== 200) {
      throw new Error((result && result.msg) || "拉取聊天消息失败");
    }

    const list = Array.isArray(result.data) ? result.data : [];
    const mapped = list.map(mapContactMessage);
    if (!areContactMessagesEqual(messages.value, mapped)) {
      messages.value = mapped;
      store.updateCurrentMessages(mapped);
      scrollToBottom();
    }
  } catch (error) {
    console.error("Contact message sync failed:", error);
  } finally {
    isContactSyncing = false;
  }
}

async function sendContactMessage(messageText) {
  try {
    const requestBody = {
      message: messageText,
      conversationId: store.activeConversationId,
      targetType: store.chatMode,
      targetId: store.chatTarget?.id,
      targetName: store.chatTarget?.name,
      senderName: store.userDisplayName,
    };

    const response = await authFetch("/user-chat/send", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(requestBody),
    });

    const result = await safeReadJson(response);
    if (!response.ok || !result || result.code !== 200) {
      throw new Error((result && result.msg) || `发送失败（${response.status}）`);
    }

    if (result?.data?.conversationId) {
      store.activeConversationId = result.data.conversationId;
    }

    await loadContactMessages();
  } catch (error) {
    console.error("Contact message send failed:", error);
    for (let i = messages.value.length - 1; i >= 0; i -= 1) {
      const message = messages.value[i];
      if (message?.optimistic && message?.text === messageText) {
        message.error = true;
        message.errorMessage = error.message || "发送失败，请稍后重试";
        message.canRetry = true;
        message.pendingSendText = messageText;
        break;
      }
    }
    store.updateCurrentMessages(messages.value);
  }
}

async function performAiChat(message, userMsgIndex) {
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
    const requestBody = {
      message,
      conversationId: requestConversationId,
    };

    const response = await authFetch("/ai/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      signal: currentAbortController.signal,
      body: JSON.stringify(requestBody),
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
    let sseBuffer = "";

    while (!done) {
      if (store.activeConversationId !== requestConversationId) {
        if (currentAbortController) currentAbortController.abort();
        break;
      }

      const { value, done: readerDone } = await reader.read();
      done = readerDone;
      if (value) {
        sseBuffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");

        let boundaryIndex = sseBuffer.indexOf("\n\n");
        while (boundaryIndex !== -1) {
          const eventBlock = sseBuffer.slice(0, boundaryIndex);
          sseBuffer = sseBuffer.slice(boundaryIndex + 2);
          processSSEEvent(eventBlock, assistantIndex);
          boundaryIndex = sseBuffer.indexOf("\n\n");
        }
      }
    }
    if (sseBuffer.trim()) {
      processSSEEvent(sseBuffer, assistantIndex);
    }
  } catch (error) {
    const hasPartialReply = Boolean(
      String(messages.value[assistantIndex]?.text || "").trim() ||
        (pendingChunkAssistantIndex === assistantIndex &&
          String(pendingChunkText || "").trim()),
    );

    if (error?.name === "AbortError") {
      console.log("--- [DEBUG] Request aborted.");
    } else {
      console.error("Chat Error:", error);
      if (
        messages.value[assistantIndex] &&
        store.activeConversationId === requestConversationId
      ) {
        if (hasPartialReply) {
          // 已经拿到可展示内容时，不再把尾部链路异常显示为“network error”。
          messages.value[assistantIndex].error = false;
          messages.value[assistantIndex].errorMessage = "";
          messages.value[assistantIndex].canRetry = false;
        } else {
          messages.value[assistantIndex].error = true;
          // 对异常进行脱敏显示
          messages.value[assistantIndex].errorMessage =
            error?.message || "网络连接异常，请检查后端服务。";
          messages.value[assistantIndex].canRetry = true;
        }
      }
    }
  } finally {
    flushPendingChunk();
    isStreaming.value = false;
    scrollToBottom();

    if (store.activeConversationId === requestConversationId) {
      store.updateCurrentMessages(messages.value);

      if (store.currentUser?.id && store.shouldRefreshHistoryTitle(requestConversationId)) {
        store.fetchHistory(true);
      }
    }
  }
}

function retry(idx) {
  if (!isAiChat.value) {
    const targetMessage = messages.value[idx];
    const retryText = targetMessage?.pendingSendText || targetMessage?.text;
    if (!retryText) return;

    targetMessage.error = false;
    targetMessage.canRetry = false;
    targetMessage.errorMessage = "";
    sendContactMessage(retryText);
    return;
  }

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
    performAiChat(userMsgText, userMsgPos);
  }
}

function handleRetry(index) {
  if (typeof index !== "number" || index < 0) return;
  retry(index);
}

function handleVisibilityChange() {
  if (
    typeof document !== "undefined" &&
    document.visibilityState === "visible" &&
    contactModeReady.value
  ) {
    loadContactMessages();
  }
}

// 辅助函数：处理 SSE 事件块并更新界面
function processSSEEvent(eventBlock, assistantIndex) {
  if (!eventBlock) return;

  const lines = eventBlock.split("\n");
  const dataLines = [];

  for (const rawLine of lines) {
    if (!rawLine) continue;
    if (rawLine.startsWith(":")) continue;

    if (rawLine.startsWith("data:")) {
      dataLines.push(rawLine.slice(5).replace(/^\s/, ""));
    } else {
      dataLines.push(rawLine);
    }
  }

  if (dataLines.length === 0) return;

  let content = dataLines.join("\n");
  if (content.trim() === "[DONE]") return;

  // 尝试解析 JSON 控制指令 (RAG/MCP)
  try {
    const json = JSON.parse(content.trim());
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

  if (!content) return;

  pendingChunkAssistantIndex = assistantIndex;
  pendingChunkText += content;
  scheduleChunkFlush();
}

onMounted(() => {
  // 每分钟刷新一次时间段问候词，确保跨时段时欢迎语自动切换。
  greetingTimer = setInterval(() => {
    greetingClockTick.value = Date.now();
  }, 60000);

  if (typeof document !== "undefined") {
    document.addEventListener("visibilitychange", handleVisibilityChange);
  }
});

onUnmounted(() => {
  stopContactPolling();

  if (greetingTimer) {
    clearInterval(greetingTimer);
    greetingTimer = null;
  }

  if (scrollRafId) {
    cancelAnimationFrame(scrollRafId);
    scrollRafId = 0;
  }
  if (streamFlushRafId) {
    cancelAnimationFrame(streamFlushRafId);
    streamFlushRafId = 0;
  }
  if (streamFlushTimer) {
    clearTimeout(streamFlushTimer);
    streamFlushTimer = 0;
  }
  flushPendingChunk();
  if (currentAbortController) {
    currentAbortController.abort();
  }
  if (typeof document !== "undefined") {
    document.removeEventListener("visibilitychange", handleVisibilityChange);
  }
});
</script>

<style scoped>
.chat-home-shell {
  position: relative;
  display: flex;
  flex-direction: column;
  flex: 1;
  height: 100%;
  min-height: 0;
}

.chat-home-shell.streaming .message-wrapper {
  animation: none !important;
}

.chat-home-shell.streaming .typing-loader span {
  animation-duration: 1.1s;
}

.chat-context-bar {
  margin: 10px 4% 0;
  padding: 6px 12px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 84%, transparent);
  background: color-mix(in srgb, var(--surface-solid) 90%, transparent);
  color: var(--text-main);
  align-self: flex-start;
}

.context-tag {
  font-size: 12px;
  color: var(--text-muted);
}

.context-name {
  font-size: 13px;
  color: var(--primary);
}

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
