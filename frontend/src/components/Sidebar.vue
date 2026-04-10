<template>
  <aside class="sidebar" id="sidebar" :class="{ collapsed: store.isSidebarCollapsed }">
    <div class="sidebar-header">
        <svg viewBox="0 0 24 24">
            <path
                d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 14H9v-2h2v2zm0-4H9V7h2v5zm4 4h-2v-2h2v2zm0-4h-2V7h2v5z" />
        </svg>
        <span>XY-AI 引擎</span>
    </div>
    <div class="menu">
        <div class="history-label">功能菜单</div>
        <div class="menu-item" id="navChat" title="聊天会话" @click="store.setView('chat')" :class="{ active: store.currentView === 'chat' }">
            <svg viewBox="0 0 24 24">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
            </svg>
            <span>聊天会话</span>
        </div>
        <div class="menu-item" id="navDB" title="向量数据库" @click="store.setView('db')" :class="{ active: store.currentView === 'db' }">
            <svg viewBox="0 0 24 24">
                <ellipse cx="12" cy="5" rx="9" ry="3"></ellipse>
                <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path>
                <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path>
            </svg>
            <span>向量数据库</span>
        </div>
    </div>

    <div class="history-container">
        <HistoryList :history="store.chatHistory" @select="store.selectConversation" />
    </div>

    <div class="user-profile" title="用户中心">
        <div class="user-profile-main" @click="!store.currentUser && store.openLogin()" style="display: flex; align-items: center; gap: 12px; flex: 1; cursor: pointer; min-width: 0;">
            <div class="user-avatar-inner" :style="avatarStyle">
                {{ store.currentUser ? store.currentUser.name.substring(0,2).toUpperCase() : '?' }}
            </div>
            <div class="user-info">
                <div class="user-name">{{ store.currentUser ? store.currentUser.name : '未登录' }}</div>
                <div class="user-status" :style="{ color: store.currentUser ? '#00e5ff' : '#94a3b8' }">
                    {{ store.currentUser ? 'Online' : 'Offline' }}
                </div>
            </div>
        </div>
        
        <button v-if="store.currentUser" class="logout-btn" title="退出登录" @click.stop="handleLogout">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path>
                <polyline points="16 17 21 12 16 7"></polyline>
                <line x1="21" y1="12" x2="9" y2="12"></line>
            </svg>
        </button>
    </div>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useUiStore } from '../store/index'
import HistoryList from './HistoryList.vue'

const store = useUiStore()
const router = useRouter()

const avatarStyle = computed(() => {
  if (!store.currentUser) return { background: '#334155', color: '#94a3b8', border: 'none' }
  return { background: '#00e5ff', color: '#fff', border: 'none' }
})

function navigateTo(path) {
  router.push(path)
}

function handleLogout() {
  store.confirmLogout()
}
</script>

<style scoped>
.user-profile {
    margin-top: auto;
    padding: 1.5rem;
    border-top: 1px solid var(--sidebar-border);
    display: flex;
    align-items: center;
    justify-content: space-between;
    transition: all 0.2s ease;
}

.user-avatar-inner {
    width: 38px;
    height: 38px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: bold;
    font-size: 14px;
    flex-shrink: 0;
}

.user-info {
    min-width: 0;
    flex: 1;
}

.logout-btn {
    background: rgba(255, 255, 255, 0.05); /* 增加一点底色 */
    border: 1px solid rgba(255, 255, 255, 0.1);
    color: #94a3b8;
    cursor: pointer;
    padding: 8px;
    border-radius: 10px;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all 0.2s;
    position: relative;
    z-index: 50; /* 极高层级 */
    flex-shrink: 0;
}

.logout-btn:hover {
    background: rgba(239, 68, 68, 0.15);
    color: #ff4d4f;
    border-color: rgba(239, 68, 68, 0.3);
}

.sidebar.collapsed .logout-btn {
    display: none;
}


body.dark .user-profile {
    border-top-color: rgba(255,255,255,0.05);
}
</style>