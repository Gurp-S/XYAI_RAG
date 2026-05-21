<template>
  <div class="floating-bg"></div>
  <Login v-if="!ui.currentUser || ui.showLogin" />
  <div
    class="app-container"
    :class="{
      'is-blurred': !ui.currentUser || ui.showLogin,
      'sidebar-collapsed': ui.isSidebarCollapsed,
      'sidebar-fullscreen': ui.isFullscreen,
    }"
  >
    <Sidebar v-if="ui.currentUser" />
    <main v-if="ui.currentUser" class="main-content-wrapper">
      <router-view />
    </main>
    <ModalManager v-if="ui.currentUser" />
    <ToastProvider />

    <!-- 公告 Toast -->
    <Teleport to="body">
      <Transition name="announce-toast">
        <div v-if="showAnnounceToast" class="announce-toast" @click="openAnnounceModal">
          <div class="announce-toast-header">
            <span class="announce-toast-icon">📢</span>
            <span class="announce-toast-title">系统公告</span>
            <button class="announce-toast-close" @click.stop="dismissAnnouncement">✕</button>
          </div>
          <div class="announce-toast-body">
            <p class="announce-toast-text">{{ truncateAnnounce(currentAnnouncement?.content, 80) }}</p>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 公告全文弹窗 -->
    <Teleport to="body">
      <Transition name="announce-modal">
        <div v-if="showAnnounceModal" class="announce-modal-overlay" @click.self="closeAnnounceModal">
          <div class="announce-modal-card">
            <div class="announce-modal-header">
              <span class="announce-modal-icon">📢</span>
              <span class="announce-modal-title">系统公告</span>
              <button class="announce-modal-close" @click="closeAnnounceModal">✕</button>
            </div>
            <div class="announce-modal-body">
              <p class="announce-modal-text">{{ currentAnnouncement?.content }}</p>
              <p v-if="currentAnnouncement?.createTime" class="announce-modal-time">{{ currentAnnouncement.createTime }}</p>
            </div>
            <div class="announce-modal-footer">
              <button class="announce-modal-btn" @click="closeAnnounceModal">我知道了</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>

    <!-- 自定义退出确认弹窗 -->
    <Teleport to="body">
      <Transition name="confirm-fade" appear>
        <div v-if="ui.showLogoutConfirm" class="confirm-overlay" style="z-index: 100000;" @click.self="ui.closeLogoutConfirm()">
          <div class="confirm-card">
            <div class="confirm-title">确认要退出登录吗？</div>
            <div class="confirm-btns">
              <button class="confirm-btn cancel" @click="ui.closeLogoutConfirm()">取消</button>
              <button class="confirm-btn danger" @click="ui.logout()">确认退出</button>
            </div>
          </div>
        </div>
      </Transition>
    </Teleport>
  </div>
</template>

<script setup>
import Sidebar from './components/Sidebar.vue'
import ToastProvider from './components/ToastProvider.vue'
import ModalManager from './components/ModalManager.vue'
import Login from './components/Login.vue'
import { useUiStore } from './store/index'
import { onMounted, onErrorCaptured, watch, ref, computed } from 'vue'
import { useRoute } from 'vue-router'

const ui = useUiStore()
const route = useRoute()
const hasRenderError = ref(false)

// ===== 公告系统 =====
const announcements = ref([])
const showAnnounceToast = ref(false)
const showAnnounceModal = ref(false)
const announceTimer = ref(null)
const DISMISSED_ANNOUNCEMENTS_KEY = 'xyai_dismissed_announcements'
const dismissedIds = ref(new Set(JSON.parse(localStorage.getItem(DISMISSED_ANNOUNCEMENTS_KEY) || '[]')))

const currentAnnouncement = computed(() => {
  if (!announcements.value.length) return null
  return announcements.value.find(a => !dismissedIds.value.has(a.id)) || null
})

function truncateAnnounce(text, max = 80) {
  if (!text) return ''
  return text.length > max ? text.substring(0, max) + '...' : text
}

function openAnnounceModal() {
  clearTimeout(announceTimer.value)
  showAnnounceToast.value = false
  showAnnounceModal.value = true
}

function closeAnnounceModal() {
  showAnnounceModal.value = false
  dismissAnnouncement()
}

function startAnnounceTimer() {
  clearTimeout(announceTimer.value)
  announceTimer.value = setTimeout(() => {
    dismissAnnouncement()
  }, 5000)
}

function showAnnouncement() {
  if (currentAnnouncement.value) {
    showAnnounceToast.value = true
    startAnnounceTimer()
  }
}

async function fetchAnnouncements() {
  try {
    const { apiGetAnnouncements } = await import('./services/api.js')
    const list = await apiGetAnnouncements()
    announcements.value = Array.isArray(list) ? list : []
    showAnnouncement()
  } catch (e) {
    // silently fail — announcements are non-critical
  }
}

function dismissAnnouncement() {
  clearTimeout(announceTimer.value)
  showAnnounceToast.value = false
  showAnnounceModal.value = false
  if (!currentAnnouncement.value) return
  dismissedIds.value.add(currentAnnouncement.value.id)
  localStorage.setItem(DISMISSED_ANNOUNCEMENTS_KEY, JSON.stringify([...dismissedIds.value]))
}

onMounted(() => {
  if (ui.currentUser) fetchAnnouncements()
})

onMounted(() => {
  if (ui.currentUser && ui.chatHistory.length === 0) {
    ui.fetchHistory()
  }
  ui.syncViewFromRoute(route.path)
})

watch(() => route.path, () => {
  ui.syncViewFromRoute(route.path)
})

// Global error boundary — prevents white screen on component crash
onErrorCaptured((err, instance, info) => {
  console.error("[App] captured error:", err, info)
  // Only show fallback for rendering errors; network errors pass through
  if (info?.includes("render") || info?.includes("setup")) {
    hasRenderError.value = true
    return false // prevent propagation
  }
  return true
})
</script>

<style>
.main-content-wrapper {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
  position: relative;
  contain: layout paint;
}

.confirm-fade-enter-active,
.confirm-fade-leave-active {
  transition: opacity 160ms ease;
}

.confirm-fade-enter-from,
.confirm-fade-leave-to {
  opacity: 0;
}

.confirm-fade-enter-active .confirm-card,
.confirm-fade-leave-active .confirm-card {
  transition:
    transform 180ms cubic-bezier(0.22, 1, 0.36, 1),
    opacity 180ms cubic-bezier(0.22, 1, 0.36, 1);
  will-change: transform, opacity;
}

.confirm-fade-enter-from .confirm-card,
.confirm-fade-leave-to .confirm-card {
  opacity: 0.94;
  transform: translateY(8px) scale(0.98);
}

/* ===== 公告 Toast ===== */
.announce-toast {
  position: fixed;
  top: 20px;
  right: 20px;
  z-index: 99999;
  width: 340px;
  background: var(--surface-solid);
  border-radius: 14px;
  border: 1px solid var(--panel-border);
  box-shadow: var(--shadow-md);
  cursor: pointer;
  overflow: hidden;
  border-left: 4px solid var(--primary);
  backdrop-filter: blur(var(--blur-soft));
}

.announce-toast-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 14px 14px 8px;
}

.announce-toast-icon {
  font-size: 16px;
  flex-shrink: 0;
}

.announce-toast-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-main);
  flex: 1;
}

.announce-toast-close {
  width: 24px;
  height: 24px;
  border-radius: 8px;
  border: 1px solid var(--panel-border);
  background: var(--surface-soft);
  color: var(--text-muted);
  font-size: 12px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
  flex-shrink: 0;
}
.announce-toast-close:hover {
  background: var(--hover-bg);
  color: var(--text-main);
}

.announce-toast-body {
  padding: 0 14px 14px;
}

.announce-toast-text {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.55;
  word-break: break-word;
}

.announce-toast:hover {
  border-color: var(--primary);
  box-shadow: var(--shadow-lg);
}

/* 公告 Toast 动画 */
.announce-toast-enter-active {
  animation: announceIn 0.3s var(--motion-ease);
}
.announce-toast-leave-active {
  animation: announceOut 0.25s ease-in;
}

@keyframes announceIn {
  from { opacity: 0; transform: translateX(40px) scale(0.95); }
  to { opacity: 1; transform: translateX(0) scale(1); }
}
@keyframes announceOut {
  from { opacity: 1; transform: translateX(0) scale(1); }
  to { opacity: 0; transform: translateX(40px) scale(0.95); }
}

/* ===== 公告全文弹窗 ===== */
.announce-modal-overlay {
  position: fixed;
  inset: 0;
  z-index: 100000;
  background: rgba(9, 16, 30, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.announce-modal-card {
  width: min(520px, 92vw);
  max-height: 80vh;
  display: flex;
  flex-direction: column;
  background: var(--surface-solid);
  border-radius: 18px;
  border: 1px solid var(--panel-border);
  box-shadow: var(--shadow-lg);
  animation: announceModalIn 0.25s var(--motion-ease);
}

.announce-modal-header {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 22px 14px;
  border-bottom: 1px solid var(--panel-border);
}

.announce-modal-icon {
  font-size: 20px;
  flex-shrink: 0;
}

.announce-modal-title {
  font-size: 16px;
  font-weight: 700;
  color: var(--text-main);
  flex: 1;
}

.announce-modal-close {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  border: 1px solid var(--panel-border);
  background: var(--surface-soft);
  color: var(--text-muted);
  font-size: 14px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s, color 0.15s;
  flex-shrink: 0;
}
.announce-modal-close:hover {
  background: var(--hover-bg);
  color: var(--text-main);
}

.announce-modal-body {
  flex: 1;
  overflow-y: auto;
  padding: 18px 22px;
}

.announce-modal-text {
  margin: 0;
  font-size: 14px;
  color: var(--text-main);
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.announce-modal-time {
  margin: 16px 0 0;
  font-size: 12px;
  color: var(--text-muted);
}

.announce-modal-footer {
  padding: 14px 22px 18px;
  display: flex;
  justify-content: flex-end;
  border-top: 1px solid var(--panel-border);
}

.announce-modal-btn {
  padding: 8px 24px;
  border-radius: 10px;
  border: none;
  background: var(--primary);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s, transform 0.15s;
}
.announce-modal-btn:hover {
  background: var(--primary-hover);
}

/* 公告弹窗动画 */
.announce-modal-enter-active {
  transition: opacity 0.2s ease;
}
.announce-modal-leave-active {
  transition: opacity 0.18s ease;
}
.announce-modal-enter-from,
.announce-modal-leave-to {
  opacity: 0;
}
.announce-modal-enter-active .announce-modal-card {
  animation: announceModalCardIn 0.25s var(--motion-ease);
}
.announce-modal-leave-active .announce-modal-card {
  animation: announceModalCardOut 0.18s ease-in;
}

@keyframes announceModalIn {
  from { opacity: 0; transform: translateY(12px) scale(0.97); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
@keyframes announceModalCardIn {
  from { opacity: 0; transform: translateY(12px) scale(0.97); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
@keyframes announceModalCardOut {
  from { opacity: 1; transform: translateY(0) scale(1); }
  to { opacity: 0; transform: translateY(8px) scale(0.97); }
}
</style>
