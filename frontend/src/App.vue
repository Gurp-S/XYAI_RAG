<template>
  <div class="floating-bg"></div>
  <Login v-if="!ui.currentUser || ui.showLogin" />
  <div
    class="app-container"
    :class="{
      'is-blurred': !ui.currentUser || ui.showLogin,
      'sidebar-collapsed': ui.isSidebarCollapsed,
    }"
  >
    <Sidebar v-if="ui.currentUser" />
    <main v-if="ui.currentUser" class="main-content-wrapper">
      <router-view />
    </main>
    <ModalManager v-if="ui.currentUser" />
    
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
import ModalManager from './components/ModalManager.vue'
import Login from './components/Login.vue'
import { useUiStore } from './store/index'
import { onMounted, onErrorCaptured, watch, ref } from 'vue'
import { useRoute } from 'vue-router'

const ui = useUiStore()
const route = useRoute()
const hasRenderError = ref(false)

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
.db-manager-view {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: transparent;
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
</style>
