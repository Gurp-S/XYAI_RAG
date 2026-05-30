<template>
  <main class="main-content chat-home-shell" :class="{ streaming: isStreaming }">
    <HeaderBar
      @toggle-sidebar="emit('toggleSidebar')"
    />

    <div v-if="!isAiChat" class="chat-context-bar">
      <span class="context-tag">{{ store.chatMode === 'group' ? '群聊' : '用户聊天' }}</span>
      <strong class="context-name">{{ chatTargetName }}</strong>
    </div>

    <div id="chatBox" ref="chatBox" class="chat-box" @scroll="handleScroll">
      <!-- 加载更早消息的提示 -->
      <div v-if="store.loadingOlder" class="older-loading-indicator">
        <span class="loading-spinner-sm"></span>
        <span>加载更早的消息...</span>
      </div>
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
          <div class="avatar">
            <img v-if="store.aiAvatar" :src="store.aiAvatar" alt="AI" class="avatar-img" />
            <template v-else>{{ assistantLabel }}</template>
          </div>
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
          :file="m.file"
          :is-streaming="isAiChat && isStreaming && idx === messages.length - 1 && m.role === 'assistant'"
          :feedback="m.feedback ?? -1"
          @retry="handleRetry(idx)"
          @copy="handleCopy(m.text)"
          @edit="handleEdit(idx, m.text)"
          @like="handleLike(idx)"
          @dislike="handleDislike(idx)"
          @accept-file="handleAcceptFile"
          @reject-file="handleRejectFile"
          @download-file="handleDownloadFile"
        />
      </template>
    </div>

    <FileAttachment
      :files="pendingFiles"
      @remove="handleRemoveFile"
    />
    <input
      ref="fileInputRef"
      type="file"
      hidden
      accept=".txt,.csv,.pdf,.doc,.docx,.md,.html,.json,.xml,.log"
      multiple
      @change="onLocalFilePicked"
    />
    <!-- 好友聊天本地文件分享专用输入 -->
    <input
      ref="localFileInputRef"
      type="file"
      hidden
      @change="onLocalFileSharePicked"
    />
    <FooterInput
      v-model="input"
      :disabled="pendingFiles.some(f => f._parsing)"
      :chatMode="store.chatMode"
      @send="send"
      @pick-db="showDbFilePicker = true"
      @pick-computer="fileInputRef?.click()"
      @pick-local-file="localFileInputRef?.click()"
    />
  </main>
  <!-- DB File Picker Modal -->
  <Teleport to="body">
    <div v-if="showDbFilePicker" class="db-picker-overlay" @click.self="showDbFilePicker = false">
      <div class="db-picker-modal">
        <div class="db-picker-header">
          <h3>从数据库选择文件</h3>
          <button class="db-picker-close" @click="showDbFilePicker = false">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/></svg>
          </button>
        </div>
        <div class="db-picker-search">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/></svg>
          <input v-model="dbFileSearch" placeholder="搜索文件名..." />
        </div>
        <div class="db-picker-list">
          <div v-if="dbFilesLoading" class="db-picker-empty">加载中...</div>
          <div v-else-if="filteredDbFiles.length === 0" class="db-picker-empty">暂无文件</div>
          <div
  v-for="f in filteredDbFiles"
  :key="f.fileId"
  class="db-file-row"
  :class="{ selected: selectedDbIds.has(f.fileId) }"
  @click="toggleDbFile(f)"
>
  <svg class="db-file-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
    <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
    <polyline points="14 2 14 8 20 8" />
  </svg>

  <div class="db-file-info">
    <div class="db-file-name">{{ f.fileName }}</div>
    <div class="db-file-preview">{{ f.contentPreview }}</div>
  </div>

  <span class="db-file-coll">{{ f.collectionName || '-' }}</span>
  <span class="db-file-meta">{{ f.chunkSize }} 字节 · {{ f.visibility || '私有' }}</span>

  <span v-if="selectedDbIds.has(f.fileId)" class="db-file-check">
    <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round">
      <polyline points="20 6 9 17 4 12" />
    </svg>
  </span>
</div>
        </div>
        <div class="db-picker-footer">
          <button class="db-picker-btn db-picker-btn-secondary" @click="showDbFilePicker = false">取消</button>
          <button class="db-picker-btn db-picker-btn-primary" @click="confirmDbFiles">确认选择 ({{ selectedDbIds.size }})</button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, ref, onMounted, onUnmounted, nextTick, watch } from "vue";
import HeaderBar from "./HeaderBar.vue";
import MessageItem from "./MessageItem.vue";
import FooterInput from "./FooterInput.vue";
import FileAttachment from "./FileAttachment.vue";
import { useFileParser, parseUploadedFile } from "../composables/useFileParser.js";
import { useUiStore } from "../store";
import { authFetch, safeReadJson, apiAcceptShare, apiRejectShare, apiShareFile } from "../services/api";
import { useToast } from "../composables/useToast.js";

const emit = defineEmits(["toggleSidebar"]);

const store = useUiStore();
const toast = useToast();
const chatBox = ref(null);
const messages = ref([]);
const input = ref("");
// 从 store 恢复上次的输入内容
input.value = store.pendingInputText || ""
// 输入内容变化时持久化到 store
watch(input, (val) => {
  store.pendingInputText = val
})
const isStreaming = ref(false);
// FileAttachment 组件内部使用 useFileParser

// File attachment state
const fileInputRef = ref(null);
const localFileInputRef = ref(null);
const pendingFiles = ref([]);
const showDbFilePicker = ref(false);
const dbFiles = ref([]);
const dbFilesLoading = ref(false);
const dbFileSearch = ref('');
const selectedDbIds = ref(new Set());

// Contact chat lazy loading state
const contactMessagesCursor = ref(null);
const contactHasMoreMessages = ref(true);
const contactLoadingOlder = ref(false);

const filteredDbFiles = computed(() => {
  if (!dbFileSearch.value) return dbFiles.value;
  const q = dbFileSearch.value.toLowerCase();
  return dbFiles.value.filter(f =>
    (f.fileName || '').toLowerCase().includes(q)
  );
});
let currentAbortController = null;
let scrollRafId = 0;
let streamFlushRafId = 0;
let streamFlushTimer = 0;
let streamHeartbeatTimer = 0;
let pendingChunkText = "";
let pendingChunkAssistantIndex = -1;
let lastStreamFlushTs = 0;
let streamLastActivityTs = 0;
let streamHeartbeatTimedOut = false;
let streamAutoRetryTimer = 0;
let contactPollingTimer = null;
let isContactSyncing = false;
let greetingTimer = null;
const greetingClockTick = ref(Date.now());
const STREAM_FLUSH_MIN_INTERVAL = 24;
const STREAM_FLUSH_FORCE_CHARS = 320;
// 延长流式心跳超时，避免在后端较慢响应时过早中止并触发重试
const STREAM_HEARTBEAT_TIMEOUT_MS = 95000;
const STREAM_HEARTBEAT_CHECK_MS = 4000;
const STREAM_AUTO_RETRY_DELAY_MS = 1200;
// 禁用自动重试以避免网络抖动或超时导致重复调用模型消耗大量 tokens
const STREAM_MAX_AUTO_RETRIES = 0;

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

  return `${phaseGreeting}，我是 XY`;
});

function buildMessageKey(message, index) {
  const baseRole = message?.role || "message";
  const preferredId =
    message?.id || message?.messageId || message?.uuid || message?.createdAt || "";
  return `${baseRole}-${preferredId || index}`;
}

// 关键：监听 Pinia 中历史消息的变化，将其应用到组件内部 messages
// 保护机制：如果 store 突然变空但本地已有消息且 activeConversationId 未变，
// 说明可能是流式发送过程中 store 被误重置，保留本地消息不丢失。
let lastSyncedConversationId = store.activeConversationId;
watch(
  () => store.currentMessages,
  (newMsgs) => {
    const incoming = newMsgs || [];
    // 当 store 消息被清空时，仅在以下情况才真正清空本地：
    // 1. 本地原本就没有消息
    // 2. activeConversationId 已变化（用户手动切换了对话）
    if (
      incoming.length === 0 &&
      messages.value.length > 0 &&
      store.activeConversationId === lastSyncedConversationId
    ) {
      return;
    }
    lastSyncedConversationId = store.activeConversationId;
    messages.value = incoming;
    // 加载更早消息时不滚动到底部（保持用户当前滚动位置）
    if (!store.loadingOlder) {
      scrollToBottom();
    }
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

function handleScroll() {
  const el = chatBox.value;
  if (!el) return;
  // 滚动到顶部（或接近顶部 50px 以内）时触发加载更早消息
  if (el.scrollTop <= 50 && !store.loadingOlder && store.hasMoreMessages) {
    store.loadOlderMessages();
  }
  // 好友聊天：滚动到顶部加载更早历史
  if (el.scrollTop <= 50 && contactModeReady.value && !contactLoadingOlder.value && contactHasMoreMessages.value) {
    loadContactOlderMessages();
  }
}

function clearStreamHeartbeatMonitor() {
  if (streamHeartbeatTimer) {
    clearInterval(streamHeartbeatTimer);
    streamHeartbeatTimer = 0;
  }
}

function clearStreamAutoRetryTimer() {
  if (streamAutoRetryTimer) {
    clearTimeout(streamAutoRetryTimer);
    streamAutoRetryTimer = 0;
  }
}

function markStreamActivity() {
  streamLastActivityTs = Date.now();
}

function startStreamHeartbeatMonitor() {
  clearStreamHeartbeatMonitor();
  markStreamActivity();
  streamHeartbeatTimer = setInterval(() => {
    if (!isStreaming.value || !currentAbortController) {
      return;
    }

    if (Date.now() - streamLastActivityTs >= STREAM_HEARTBEAT_TIMEOUT_MS) {
      streamHeartbeatTimedOut = true;
      currentAbortController.abort();
    }
  }, STREAM_HEARTBEAT_CHECK_MS);
}

function scheduleAutoRetry(message, userMsgIndex, attemptCount, conversationId, messageCount) {
  clearStreamAutoRetryTimer();
  streamAutoRetryTimer = setTimeout(() => {
    streamAutoRetryTimer = 0;
    if (
      store.activeConversationId !== conversationId ||
      messages.value.length !== messageCount
    ) {
      return;
    }
    performAiChat(message, userMsgIndex, { attemptCount });
  }, STREAM_AUTO_RETRY_DELAY_MS);
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
  markStreamActivity();
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

// ======================== 文件附件处理 ========================

function onLocalFilePicked(e) {
  const selected = Array.from(e.target.files || []);
  if (selected.length) handleAddFiles(selected);
  e.target.value = '';
}

async function handleAddFiles(files) {
  // 用户聊天模式：解析文件后作为文本消息直接发送
  if (!isAiChat.value) {
    for (const file of files) {
      try {
        const data = await parseUploadedFile(file);
        const preview = (data.content || '').replace(/\s+/g, ' ').trim().slice(0, 200);
        const msgText = `[分享文件: ${file.name}]\n${preview}`;
        await sendContactMessage(msgText);
      } catch (err) {
        toast.error(`解析文件 "${file.name}" 失败`);
      }
    }
    await loadContactMessages();
    return;
  }

  // AI 聊天模式：添加为文件附件（原有逻辑不变）
  for (const file of files) {
    const baseEntry = {
      name: file.name,
      size: file.size,
      type: file.type,
      _parsing: true,
      _error: '',
      parsed: false,
      content: '',
      contentType: '',
    };
    const idx = pendingFiles.value.push(baseEntry) - 1;
    try {
      const data = await parseUploadedFile(file);
      pendingFiles.value[idx] = {
        ...baseEntry,
        _parsing: false,
        parsed: true,
        content: data.content,
        contentType: data.contentType,
      };
      console.log('解析成功，返回数据:', data); 
    } catch (err) {
      pendingFiles.value[idx] = {
        ...baseEntry,
        _parsing: false,
        _error: err.message || '解析失败',
      };
    }
  }
}

function handleRemoveFile(idx) {
  pendingFiles.value.splice(idx, 1);
}

/** 好友聊天：选择本地文件后分享到聊天 */
async function onLocalFileSharePicked(e) {
  const files = Array.from(e.target.files || []);
  e.target.value = '';
  if (!files.length || !contactModeReady.value) return;

  const targetId = store.chatTarget?.id;
  const targetType = store.chatMode;
  const formData = new FormData();
  formData.append('file', files[0]);
  formData.append('targetType', targetType);
  formData.append('targetId', targetId);
  formData.append('senderName', store.userDisplayName);

  try {
    const response = await authFetch('/user-chat/share/local-file', {
      method: 'POST',
      body: formData,
    });
    const result = await safeReadJson(response);
    if (result?.code === 200) {
      toast.success(`已分享文件: ${files[0].name}`);
    } else {
      toast.error((result && result.msg) || '分享文件失败');
    }
  } catch (err) {
    toast.error('分享文件网络请求失败');
  }
  await loadContactMessages();
}

/** 好友聊天：滚动到顶部时加载更早的历史消息 */
async function loadContactOlderMessages() {
  if (contactLoadingOlder.value || !contactHasMoreMessages.value || !contactModeReady.value) return;
  if (!messages.value.length) return;

  const oldest = messages.value[0];
  const cursor = oldest?.id || oldest?.createdAt;
  if (!cursor) return;

  contactLoadingOlder.value = true;
  try {
    const query = new URLSearchParams({
      targetType: String(store.chatMode),
      targetId: String(store.chatTarget.id),
      cursor: String(cursor),
      limit: '20',
    });
    const response = await authFetch(`/user-chat/messages?${query.toString()}`, {
      method: 'POST',
    });
    const result = await safeReadJson(response);
    if (!response.ok || !result || result.code !== 200) throw new Error('加载历史消息失败');

    const list = Array.isArray(result.data) ? result.data : [];
    if (!list.length) {
      contactHasMoreMessages.value = false;
      return;
    }

    const mapped = list.map(mapContactMessage);
    // 去重
    const existingIds = new Set(messages.value.map(m => m.id || m.createdAt));
    const deduped = mapped.filter(m => !existingIds.has(m.id || m.createdAt));

    if (deduped.length) {
      messages.value = [...deduped, ...messages.value];
      store.updateCurrentMessages(messages.value);
    }
    if (list.length < 20) {
      contactHasMoreMessages.value = false;
    }
  } catch (err) {
    console.error('Failed to load older contact messages:', err);
  } finally {
    contactLoadingOlder.value = false;
  }
}

function toggleDbFile(f) {
  const id = f.fileId;          // 不再用 fileChunkId
  const s = new Set(selectedDbIds.value);
  if (s.has(id)) s.delete(id); else s.add(id);
  selectedDbIds.value = s;
}

async function confirmDbFiles() {
  const selected = dbFiles.value.filter(f => selectedDbIds.value.has(f.fileId));
  showDbFilePicker.value = false;
  selectedDbIds.value = new Set();

  if (!isAiChat.value) {
    // 用户聊天模式：直接分享文件给聊天目标
    const targetId = store.chatTarget?.id;
    if (!targetId) {
      toast.error('未选择聊天目标');
      return;
    }
    let sharedCount = 0;
    for (const f of selected) {
      try {
        const result = await apiShareFile(f.collectionName, f.fileId, targetId, undefined, f.fileName);
        if (result?.code === 200) {
          sharedCount++;
        } else {
          toast.error(`分享 "${f.fileName}" 失败: ${(result && result.msg) || ''}`);
        }
      } catch (e) {
        toast.error(`分享 "${f.fileName}" 网络请求失败`);
      }
    }
    if (sharedCount > 0) {
      toast.success(`已分享 ${sharedCount} 个文件`);
      await loadContactMessages();
    }
    return;
  }

  // AI 聊天模式：添加为文件附件（原有逻辑不变）
  const entries = selected.map(f => ({
    name: f.fileName || '数据库文件',
    type: 'text/plain',
    _parsing: false,
    _error: '',
    parsed: true,
    content: `[数据库文件: ${f.fileName}]`,
    contentType: '',
    dbFileId: f.fileId,
  }));
  pendingFiles.value.push(...entries);
}

// ======================== 消息发送 ========================

async function send() {
  if (!input.value.trim() || isStreaming.value) return;
  // 如果有文件正在解析中，阻止发送
  if (pendingFiles.value.some(f => f._parsing)) return;

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
    // 发送后清空文件附件
    pendingFiles.value = [];
    return;
  }

  // AI 模式：本地先推送用户消息，再进入 SSE 流式回复。
  store.ensureAiConversationEntry(store.activeConversationId, userText);
  messages.value.push({ role: "user", text: userText, rag: false });
  scrollToBottom();

  store.setConversationPreviewTitle(store.activeConversationId, userText);
  await performAiChat(userText, messages.value.length);
  // AI 模式下发送后清空文件附件
  pendingFiles.value = [];
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

  const rawText = String(item?.content ?? "");
  let text = rawText;
  let file = null;

  // 检测是否为 file_share JSON 负载
  if (rawText.startsWith('{')) {
    try {
      const parsed = JSON.parse(rawText);

      if (parsed?.type === 'local_file_share') {
        file = {
          type: 'local_file_share',
          name: parsed.fileName || '本地文件',
          fileSize: parsed.fileSize || 0,
          fileId: parsed.fileId || '',
          senderId: parsed.senderId || item?.senderId || '',
          senderName: parsed.senderName || '',
          status: item?.status || 'accepted',
          isMine,
          conversationId: item?.conversationId || '',
          messageId: item?.id ?? null,
        };
        text = '[分享本地文件]';
      } else if (parsed?.type === 'file_share') {
        // 解析 doc_id 格式 "filePart:000042" → 展示为 "....:42"
        const rawDocId = parsed.fileId || '';
        let shortFileId = rawDocId;
        let chunkDisplay = '';
        const colonIdx = rawDocId.lastIndexOf(':');
        if (colonIdx > 0 && colonIdx < rawDocId.length - 1) {
          shortFileId = rawDocId.slice(0, colonIdx);
          const chunkNum = parseInt(rawDocId.slice(colonIdx + 1), 10);
          if (!isNaN(chunkNum)) {
            chunkDisplay = String(chunkNum);
          }
        }
        file = {
          name: parsed.fileName || (chunkDisplay ? `....:${chunkDisplay}` : '分享的文件'),
          docId: rawDocId,
          fileIdPart: shortFileId,
          collectionName: parsed.collectionName || '',
          fileId: rawDocId,
          chunkId: parsed.chunkId ?? null,
          senderId: item?.senderId || '',
          senderName: item?.senderName || '',
          status: item?.status || 'pending',
          isMine,
          conversationId: item?.conversationId || '',
          messageId: item?.id ?? null,
        };
        text = '[分享文件]';
      }
    } catch (_) {
      // 不是 JSON，按普通文本处理
    }
  }

  return {
    role: isMine ? "user" : "assistant",
    text,
    file,
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

async function handleAcceptFile(file) {
  if (!file || !file.fileId || !file.senderId) return;
  const currentUserId = store.currentUser?.id;
  if (!currentUserId) {
    toast.error('未登录');
    return;
  }
  try {
    const result = await apiAcceptShare(
      file.collectionName,
      file.fileId,
      file.senderId,
      file.chunkId || 0,
    );
    if (result?.code === 200) {
      // 持久化 accepted 状态到 DB
      if (file.messageId) {
        authFetch(`/user-chat/messages/${file.messageId}/status?status=accepted`, { method: 'POST' }).catch(() => {});
      }
      for (const msg of messages.value) {
        if (msg.file?.fileId === file.fileId && msg.file?.senderId === file.senderId) {
          msg.file.status = 'accepted';
          break;
        }
      }
      store.updateCurrentMessages(messages.value);
      toast.success('已接收文件分享');
    } else {
      toast.error((result && result.msg) || '接收失败');
    }
  } catch (e) {
    toast.error('网络请求失败');
  }
}

async function handleRejectFile(file) {
  if (!file || !file.conversationId || !file.messageId) {
    toast.error('无法获取消息信息');
    return;
  }
  try {
    // 持久化 rejected 状态到 DB
    if (file.messageId) {
      authFetch(`/user-chat/messages/${file.messageId}/status?status=rejected`, { method: 'POST' }).catch(() => {});
    }
    const result = await apiRejectShare(file.conversationId, file.messageId);
    if (result?.code === 200) {
      // 从本地消息列表中移除该消息
      messages.value = messages.value.filter(msg =>
        !(msg.file?.conversationId === file.conversationId && msg.file?.messageId === file.messageId)
      );
      store.updateCurrentMessages(messages.value);
      toast.success('已拒绝文件分享');
    } else {
      toast.error((result && result.msg) || '拒绝失败');
    }
  } catch (e) {
    toast.error('网络请求失败');
  }
}

async function handleDownloadFile(file) {
  if (!file?.fileId) return;
  try {
    const response = await authFetch(`/user-chat/files/${file.fileId}`, { method: 'GET' });
    if (!response.ok) {
      let errMsg = '文件下载失败';
      try { const e = await response.json(); errMsg = (e && e.msg) || errMsg; } catch (_) {}
      toast.error(errMsg);
      return;
    }
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = file.name || 'download';
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    // 延迟释放以完成下载
    setTimeout(() => URL.revokeObjectURL(url), 5000);
  } catch (e) {
    console.error('Download failed:', e);
    toast.error('文件下载失败');
  }
}

async function sendContactMessage(messageText) {
  try {
    // 用户对话用后端生成 conversationId，不传客户端自定义值
    const convId = /^\d+$/.test(String(store.activeConversationId || ""))
      ? store.activeConversationId
      : undefined;

    const requestBody = {
      message: messageText,
      conversationId: convId,
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

async function performAiChat(message, userMsgIndex, options = {}) {
  const attemptCount = Number(options?.attemptCount || 0);
  isStreaming.value = true;
  streamHeartbeatTimedOut = false;
  clearStreamAutoRetryTimer();
  let autoRetryPending = false;

  // 推送或重用助手占位
  let assistantIndex = messages.value.findIndex(
    (m, i) => i >= userMsgIndex && m.role === "assistant",
  );
  if (assistantIndex === -1) {
    messages.value.push({
      role: "assistant",
      text: "",
      rag: false,
      ragData: null,
      error: false,
      canRetry: false,
      errorMessage: "",
      mcpStatus: "",
    });
    assistantIndex = messages.value.length - 1;
  } else {
    // 重置状态
    messages.value[assistantIndex].text = "";
    messages.value[assistantIndex].error = false;
    messages.value[assistantIndex].canRetry = false;
    messages.value[assistantIndex].mcpStatus = "";
    messages.value[assistantIndex].errorMessage = "";
    messages.value[assistantIndex].rag = false;
    messages.value[assistantIndex].ragData = null;
  }

  // 确保 conversationId 为数字字符串，防止后端 Long 反序列化失败
  let requestConversationId = /^\d+$/.test(String(store.activeConversationId || ""))
    ? store.activeConversationId
    : undefined;
  if (currentAbortController) {
    currentAbortController.abort();
  }
  currentAbortController = new AbortController();
  startStreamHeartbeatMonitor();

  try {
    const parsedFiles = pendingFiles.value.filter(f => f.parsed && f.content);
    const filesParam = parsedFiles.length
      ? parsedFiles.map(f => f.content).join('\n\n---\n\n')
      : undefined;

    const requestBody = {
      message,
      conversationId: requestConversationId,
    };
    if (filesParam) requestBody.files = filesParam;

    // 为每次请求生成唯一 request id，便于后端日志关联和去重排查
    const requestId =
      typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
        ? crypto.randomUUID()
        : `req_${Date.now()}_${Math.random().toString(16).slice(2)}`;

    const response = await authFetch("/ai/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Request-Id": requestId },
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
        markStreamActivity();
        sseBuffer += decoder.decode(value, { stream: true }).replace(/\r\n/g, "\n");

        let boundaryIndex = sseBuffer.indexOf("\n\n");
        while (boundaryIndex !== -1) {
          const eventBlock = sseBuffer.slice(0, boundaryIndex);
          sseBuffer = sseBuffer.slice(boundaryIndex + 2);
          const evtType = processSSEEvent(eventBlock, assistantIndex, message);
          if (evtType === "start") requestConversationId = store.activeConversationId;
          boundaryIndex = sseBuffer.indexOf("\n\n");
        }
      }
    }
    if (sseBuffer.trim()) {
      const evtType = processSSEEvent(sseBuffer, assistantIndex, message);
      if (evtType === "start") requestConversationId = store.activeConversationId;
    }
  } catch (error) {
    const shouldRetryAuto =
      store.activeConversationId === requestConversationId &&
      attemptCount < STREAM_MAX_AUTO_RETRIES &&
      (streamHeartbeatTimedOut || error?.name !== "AbortError");

    const hasPartialReply = Boolean(
      String(messages.value[assistantIndex]?.text || "").trim() ||
        (pendingChunkAssistantIndex === assistantIndex &&
          String(pendingChunkText || "").trim()),
    );

    if (shouldRetryAuto) {
      autoRetryPending = true;
      if (messages.value[assistantIndex]) {
        messages.value[assistantIndex].error = false;
        messages.value[assistantIndex].canRetry = false;
        messages.value[assistantIndex].errorMessage =
          streamHeartbeatTimedOut ? "网络较弱，正在重连..." : "连接中断，正在重试...";
      }
      scheduleAutoRetry(
        message,
        userMsgIndex,
        attemptCount + 1,
        requestConversationId,
        messages.value.length,
      );
      return;
    }

    if (error?.name === "AbortError") {
      /* request intentionally cancelled */
    } else {
      console.error("Chat Error:", error);
      if (
        messages.value[assistantIndex] &&
        store.activeConversationId === requestConversationId
      ) {
        if (hasPartialReply) {
          // 已经拿到可展示内容时，不再把尾部链路异常显示为"network error"。
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
    clearStreamHeartbeatMonitor();
    isStreaming.value = false;
    scrollToBottom();

    if (!autoRetryPending && store.activeConversationId === requestConversationId) {
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

function handleCopy(text) {
  if (!text) return;
  navigator.clipboard.writeText(text).then(() => {
    // 可选：加一个复制成功的提示
  }).catch((err) => {
    console.error("复制失败:", err);
  });
}

function handleEdit(index, text) {
  if (typeof index !== "number" || index < 0 || !text) return;
  // 把用户消息放回输入框，并滚动到输入区
  input.value = text;
  // 保持消息可见，不删除气泡
}

function handleLike(index) {
  if (typeof index !== "number" || index < 0) return;
  const msg = messages.value[index];
  if (!msg || msg.role !== "assistant") return;
  // 切换点赞状态：已赞->取消(-1), 否则->赞(1)
  const newFeedback = msg.feedback === 1 ? -1 : 1;
  msg.feedback = newFeedback;
  sendFeedback(msg, newFeedback);
}

function handleDislike(index) {
  if (typeof index !== "number" || index < 0) return;
  const msg = messages.value[index];
  if (!msg || msg.role !== "assistant") return;
  // 切换点踩状态：已踩->取消(-1), 否则->踩(0)
  const newFeedback = msg.feedback === 0 ? -1 : 0;
  msg.feedback = newFeedback;
  sendFeedback(msg, newFeedback);
}

async function sendFeedback(msg, feedbackValue) {
  const conversationId = store.activeConversationId;
  const chatMessageId = msg.id || "";
  if (!conversationId || !chatMessageId) {
    console.warn("反馈发送失败: 缺少 conversationId 或 chatMessageId");
    return;
  }
  try {
    // 使用 query params 方式发送，兼容 @RequestParam
    const query = new URLSearchParams({
      conversationId,
      chatMessageId,
      feedback: String(feedbackValue),
    }).toString();
    const response = await authFetch(`/evaluate/user?${query}`, {
      method: "POST",
    });
    const result = await safeReadJson(response);
    if (response.ok && result?.code === 200) {
      /* feedback sent successfully */
    } else {
    }
  } catch (err) {
    console.error("[反馈] 发送异常:", err);
  }
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
let _sseStartUserMessage = "";

function processSSEEvent(eventBlock, assistantIndex, userMessage) {
  if (!eventBlock) return;
  if (userMessage !== undefined) _sseStartUserMessage = userMessage;

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
    if (json.type === "start") {
      store.handleSseStart(json.conversationId, json.chatMessageId, _sseStartUserMessage);
      return "start";
    }
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

  // 将流片段追加到待刷新的缓冲区，统一由渲染器做语法规范化与修正。
  pendingChunkAssistantIndex = assistantIndex;
  pendingChunkText += content;
  scheduleChunkFlush();
}

// 数据库文件选择器：打开时加载文件列表
watch(showDbFilePicker, async (val) => {
  if (val) {
    dbFilesLoading.value = true;
    try {
      const resp = await authFetch('/milvus/metadata/user');
      const data = await safeReadJson(resp);
      const list = data?.data || [];

      dbFiles.value = (data?.data || []).map((item, idx) => {
  // 1. 解析 metadata（它在后端是 JSON 字符串或对象）
  let meta = {};
  try {
    meta = typeof item.metadata === 'string' ? JSON.parse(item.metadata) : (item.metadata || {});
  } catch (e) {
    console.warn('解析 metadata 失败:', item.metadata);
  }

  // 2. 提取各字段
  const docId = item.doc_id || '';
  const fileId = docId || `file_${idx}`;
  const fileName = meta.fileName || '未命名文件';
  const chunkSize = Number(meta.chunkSize ?? 0);
  const visibility = meta.visibility || 'private';
  const createTime = meta.createTime || '';
  const content = item.content || '';
  
  // 内容前十个字（去掉换行和多余空格）
  const cleanContent = content.replace(/\s+/g, ' ').trim();
  const contentPreview = cleanContent.length > 10 ? cleanContent.slice(0, 10) + '…' : cleanContent;

  // 3. 关键：直接取 item.collectionName，后端已经包含
  const collectionName = item.collectionName || '';

  return {
    fileId,
    fileName,
    chunkSize,
    visibility,
    createTime,
    collectionName,
    content,
    contentPreview,
    docId,
    ...item,
    ...meta
  };
});
    } catch (e) {
      dbFiles.value = [];
    } finally {
      dbFilesLoading.value = false;
    }
  }
});

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
  clearStreamHeartbeatMonitor();
  clearStreamAutoRetryTimer();
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

.db-file-meta {
  font-size: 11px;
  color: var(--text-muted);
  flex-shrink: 0;
  margin-left: auto;
  white-space: nowrap;
  background: color-mix(in srgb, var(--bg-hover) 40%, transparent);
  padding: 1px 8px;
  border-radius: 999px;
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

.older-loading-indicator {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 12px 0;
  color: var(--text-muted);
  font-size: 13px;
}
.loading-spinner-sm {
  width: 14px;
  height: 14px;
  border: 2px solid var(--panel-border);
  border-top-color: var(--accent);
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@media (max-width: 768px) {
  .older-loading-indicator {
    padding: 8px 0;
    font-size: 12px;
  }
}

/* DB file picker modal — unscoped styles for Teleport to body */
.db-picker-overlay {
  position: fixed; inset: 0; z-index: 9999;
  display: flex; align-items: center; justify-content: center;
  background: rgba(8,13,24,0.35);
  animation: dbFadeIn 0.15s ease;
}
.db-picker-modal {
  width: min(560px, 92vw);
  height: 50vh;                /* 固定高度，而不是 max-height */
  display: flex;
  flex-direction: column;
  border-radius: 18px;
  border: 1px solid var(--panel-border);
  background: var(--surface-solid);
  box-shadow: 0 24px 58px rgba(38,68,126,0.22);
  overflow: hidden;            /* 子元素溢出隐藏 */
}
.db-picker-header {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 18px;
  border-bottom: 1px solid var(--panel-border, rgba(148,170,209,0.62));
}
.db-picker-header h3 {
  margin: 0; font-size: 15px; font-weight: 700; color: var(--text-main, #11233f);
}
.db-picker-close {
  width: 32px; height: 32px; border-radius: 9px;
  border: 1px solid var(--panel-border, rgba(148,170,209,0.62));
  background: transparent; color: var(--text-muted, #617391);
  display: grid; place-items: center; cursor: pointer;
  transition: background 0.12s, color 0.12s;
}
.db-picker-close:hover { background: var(--hover-bg, rgba(15,98,254,0.1)); color: var(--text-main, #11233f); }
.db-picker-search {
  display: flex; align-items: center; gap: 8px;
  margin: 12px 18px 6px;
  padding: 0 12px;
  border-radius: 10px;
  border: 1px solid var(--panel-border, rgba(148,170,209,0.62));
  background: var(--glass-soft, rgba(247,251,255,0.9));
}
.db-picker-search svg {
  width: 15px; height: 15px; flex-shrink: 0;
  color: var(--text-muted, #617391);
}
.db-picker-search input {
  flex: 1; border: none; background: transparent;
  padding: 10px 0; font-size: 14px; color: var(--text-main, #11233f); outline: none;
}
.db-picker-search input::placeholder { color: var(--text-muted, #617391); }
.db-picker-list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 6px 14px 10px;
  scrollbar-width: thin;
  scrollbar-color: transparent transparent;
}
.db-picker-list:hover {
  scrollbar-color: color-mix(in srgb, var(--text-muted) 15%, transparent) transparent;
}
.db-picker-list::-webkit-scrollbar {
  width: 5px;
  height: 5px;
}
.db-picker-list::-webkit-scrollbar-track {
  background: transparent;
}
.db-picker-list::-webkit-scrollbar-thumb {
  background: transparent;
  border-radius: 999px;
}
.db-picker-list:hover::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--text-muted) 15%, transparent);
}
.db-picker-list::-webkit-scrollbar-thumb:hover {
  background: color-mix(in srgb, var(--text-muted) 25%, transparent);
}
.db-picker-empty {
  text-align: center; padding: 28px 0; color: var(--text-muted, #617391); font-size: 13px;
}
.db-file-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.db-file-name {
  font-weight: 600;
  font-size: 13px;
  color: var(--text-main);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.db-file-preview {
  font-size: 11.5px;
  color: var(--text-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  background: color-mix(in srgb, var(--bg-hover) 55%, transparent);
  border-radius: 4px;
  padding: 2px 7px;
  font-family: "Microsoft YaHei", sans-serif;
  letter-spacing: 0.01em;
  display: inline-block;
  max-width: 100%;
  border: 1px solid color-mix(in srgb, var(--panel-border) 35%, transparent);
  line-height: 1.5;
}
.db-file-preview::before {
  content: "↳ ";
  opacity: 0.55;
  font-family: inherit;
}
.db-file-coll {
  font-size: 11.5px;
  color: var(--text-muted);
  flex-shrink: 0;
  margin-left: 8px;
  background: color-mix(in srgb, var(--primary) 9%, transparent);
  padding: 2px 9px;
  border-radius: 999px;
  font-weight: 530;
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 1px solid color-mix(in srgb, var(--primary) 20%, transparent);
}
.db-file-row {
  display: flex; align-items: center; gap: 10px;
  padding: 10px 12px; margin: 3px 0;
  border-radius: 10px; cursor: pointer;
  border: 1px solid transparent;
  transition: background 0.12s, border-color 0.12s;
}
.db-file-row:hover { background: var(--hover-bg, rgba(15,98,254,0.1)); }
.db-file-row.selected {
  background: rgba(15,98,254,0.08);
  border-color: rgba(15,98,254,0.35);
}
.db-file-icon {
  width: 18px; height: 18px; flex-shrink: 0;
  color: var(--text-muted, #617391);
}
.db-file-check {
  width: 22px; height: 22px; border-radius: 50%;
  background: var(--primary, #0f62fe); color: #fff;
  display: grid; place-items: center; flex-shrink: 0;
}
.db-picker-footer {
  display: flex; justify-content: flex-end; gap: 8px;
  padding: 12px 18px;
  border-top: 1px solid var(--panel-border, rgba(148,170,209,0.62));
  background: var(--surface-soft, rgba(244,249,255,0.9));
}
.db-picker-btn {
  padding: 8px 16px; border-radius: 10px; font-size: 13px; font-weight: 600; cursor: pointer;
  border: 1px solid var(--panel-border, rgba(148,170,209,0.62));
  transition: background 0.12s, transform 0.12s;
}
.db-picker-btn-secondary {
  background: var(--glass-soft, rgba(247,251,255,0.9)); color: var(--text-secondary, #314768);
}
.db-picker-btn-secondary:hover { background: var(--hover-bg, rgba(15,98,254,0.1)); }
.db-picker-btn-primary {
  background: var(--primary, #0f62fe); color: #fff; border-color: transparent;
  box-shadow: 0 8px 18px rgba(15,98,254,0.22);
}
.db-picker-btn-primary:hover { filter: brightness(1.06); transform: translateY(-1px); }
@keyframes dbFadeIn { from { opacity: 0; } to { opacity: 1; } }
@keyframes dbSlideUp { from { opacity: 0; transform: translateY(10px) scale(0.985); } to { opacity: 1; transform: translateY(0) scale(1); } }
</style>
