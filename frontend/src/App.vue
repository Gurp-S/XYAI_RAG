<template>
  <div class="floating-bg"></div>
  <Login v-if="!ui.currentUser || ui.showLogin" style="position: fixed; z-index: 99999;" />
  <div class="app-container" :class="{ 'is-blurred': !ui.currentUser || ui.showLogin }">
    <Sidebar v-if="ui.currentUser" />
    <template v-if="ui.currentUser">
      <router-view v-if="ui.currentView === 'chat'" />
      <div v-else-if="ui.currentView === 'db'" class="chat-main" style="flex: 1; display: flex; flex-direction: column; overflow: hidden; background: rgba(0,0,0,0.1); backdrop-filter: blur(20px);">
        <div class="chat-header" style="height: 64px; border-bottom: 1px solid rgba(255,255,255,0.05); display: flex; align-items: center; padding: 0 24px; background: rgba(0,0,0,0.2);">
          <h2 style="font-size: 18px; font-weight: 600; color: #fff; display: flex; align-items: center; gap: 10px;">
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <ellipse cx="12" cy="5" rx="9" ry="3"></ellipse>
              <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path>
              <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path>
            </svg>
            向量数据库管理
          </h2>
        </div>
        <div style="flex: 1; overflow-y: auto;">
          <MilvusManager />
        </div>
      </div>
    </template>
    <ModalManager v-if="ui.currentUser" />
    
    <!-- 自定义退出确认弹窗 -->
    <Teleport to="body">
      <div v-if="ui.showLogoutConfirm" class="confirm-overlay" style="z-index: 10000;" @click.self="ui.closeLogoutConfirm()">
        <div class="confirm-card">
          <div class="confirm-title">确认要退出登录吗？</div>
          <div class="confirm-btns">
            <button class="confirm-btn cancel" @click="ui.closeLogoutConfirm()">取消</button>
            <button class="confirm-btn danger" @click="ui.logout()">确认退出</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import Sidebar from './components/Sidebar.vue'
import ModalManager from './components/ModalManager.vue'
import MilvusManager from './components/MilvusManager.vue'
import Login from './components/Login.vue'
import { useUiStore } from './store/index'
import { onMounted } from 'vue'

const ui = useUiStore()

onMounted(() => {
  // 如果已登录但没有历史记录，初始化拉取一次
  if (ui.currentUser && ui.chatHistory.length === 0) {
    ui.fetchHistory()
  }
})
</script>

<style>
</style>
