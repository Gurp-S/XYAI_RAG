<template>
  <div class="floating-bg"></div>
  <Login v-if="!ui.currentUser || ui.showLogin" style="position: fixed; z-index: 99999;" />
  <div class="app-container" :class="{ 'is-blurred': !ui.currentUser || ui.showLogin }">
    <Sidebar v-if="ui.currentUser" />
    <template v-if="ui.currentUser">
      <router-view v-if="ui.currentView === 'chat'" />
      <div v-else-if="ui.currentView === 'db'" class="chat-main" style="flex: 1; display: flex; flex-direction: column; overflow: hidden; background: transparent;">
        <MilvusManager style="flex: 1; overflow: hidden;" />
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
