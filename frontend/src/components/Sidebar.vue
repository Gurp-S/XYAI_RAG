<template>
  <aside class="sidebar" :class="{ collapsed }">
    <div class="sidebar-header">
      <svg viewBox="0 0 24 24"><path d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 14H9v-2h2v2zm0-4H9V7h2v5zm4 4h-2v-2h2v2zm0-4h-2V7h2v5z"/></svg>
      <span>XY-AI 引擎</span>
      <button class="sidebar-toggle" @click="$emit('toggle')">☰</button>
    </div>

    <div class="menu">
      <div class="history-label">功能菜单</div>
      <router-link to="/" class="menu-item" active-class="active">智能问答</router-link>
      <div class="menu-item">上传知识库</div>
      <div class="menu-item">向量数据库</div>
    </div>

    <div class="history-container">
      <div class="history-label">历史对话</div>
      <div id="historyList">
        <!-- 动态加载历史 -->
      </div>
    </div>

    <div class="user-profile" @click="openLogin">
      <img :src="avatarUrl" class="user-avatar-img" alt="User">
      <div class="user-info">
        <div class="user-name">{{ userName }}</div>
        <div class="user-status">Online</div>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { useUiStore } from '../store/index'
const props = defineProps({ collapsed: Boolean })
const ui = useUiStore()
const userName = computed(() => ui.currentUser?.name || 'Admin User')
const avatarUrl = computed(() => ui.currentUser?.avatar || 'https://ui-avatars.com/api/?name=' + encodeURIComponent(userName.value))
function openLogin() { ui.openLogin() }
</script>

<style scoped>
.sidebar{width:280px}
.sidebar-header{display:flex;align-items:center;gap:8px;padding:12px}
.user-profile{display:flex;align-items:center;gap:8px;padding:12px;cursor:pointer}
.user-avatar-img{width:34px;height:34px;border-radius:50%}
</style>

