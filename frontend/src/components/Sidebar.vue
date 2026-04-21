<template>
  <aside id="sidebar" class="sidebar" :class="{ collapsed: store.isSidebarCollapsed }">
    <div class="sidebar-header">
        <svg viewBox="0 0 24 24">
            <path
                d="M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 14H9v-2h2v2zm0-4H9V7h2v5zm4 4h-2v-2h2v2zm0-4h-2V7h2v5z" />
        </svg>
        <span>XY-AI 引擎</span>
    </div>
    <div class="menu">
        <div class="history-label">工作区</div>
        <div id="navChat" class="menu-item" title="聊天会话" :class="{ active: isChatHomeActive }" @click="switchToChat">
            <svg viewBox="0 0 24 24">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
            </svg>
            <span>聊天会话</span>
        </div>
        <div id="navDB" class="menu-item" title="向量数据库" :class="{ active: isDbRoute }" @click="switchToDb">
            <svg viewBox="0 0 24 24">
                <ellipse cx="12" cy="5" rx="9" ry="3"></ellipse>
                <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path>
                <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path>
            </svg>
            <span>向量数据库</span>
        </div>
    </div>

    <div class="history-container">
        <div v-if="!store.isSidebarCollapsed" class="history-label">历史对话</div>
        <HistoryList
            :history="store.chatHistory"
            :active-conversation-id="store.activeConversationId"
            @select="handleSelectConversation"
        />
    </div>

    <div class="user-profile" title="用户中心" @click.stop="toggleUserCenter()">
        <div class="user-profile-main">
                        <div class="user-avatar-inner" :class="{ offline: !store.currentUser }">
                                <template v-if="store.currentUser && store.currentUser.avatar">
                                    <img :src="store.currentUser.avatar" alt="avatar" class="avatar-img" />
                                </template>
                                <template v-else>
                                    {{ store.currentUser ? store.userAvatarText : '?' }}
                                </template>
                        </div>
            <div class="user-info">
                <div class="user-name">{{ store.currentUser ? store.userDisplayName : '未登录' }}</div>
                <div class="user-status" :class="{ offline: !store.currentUser }">
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
    <UserCenter :is-open="showUserCenter" @close="showUserCenter = false" />
  </aside>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useUiStore } from '../store/index'
import HistoryList from './HistoryList.vue'
import UserCenter from './UserCenter.vue'

const store = useUiStore()
const route = useRoute()
const showUserCenter = ref(false)

const isChatHomeActive = computed(() =>
    route.path === '/' || route.path.startsWith('/chat'),
)
const isDbRoute = computed(() => route.path.startsWith('/db'))

function toggleUserCenter() {
    if (!store.currentUser) {
        store.openLogin()
        return
    }
    showUserCenter.value = !showUserCenter.value
}

function switchToChat() {
    store.setAiChatContext()
}

function switchToDb() {
    store.setView('db')
}

function handleSelectConversation(session) {
    store.setView('chat')
    store.selectConversation(session)
}

function handleLogout() {
    showUserCenter.value = false
    store.confirmLogout()
}
</script>

<style scoped>
.user-profile-main {
    display: flex;
    align-items: center;
    gap: 12px;
    flex: 1;
    cursor: pointer;
    min-width: 0;
}

.user-avatar-inner {
    width: 38px;
    height: 38px;
    border-radius: 12px;
    display: flex;
    align-items: center;
    justify-content: center;
    font-weight: 700;
    font-size: 14px;
    flex-shrink: 0;
    color: #fff;
    background: linear-gradient(135deg, var(--primary), var(--secondary));
    box-shadow: 0 8px 16px rgba(22, 42, 86, 0.22);
}

.user-avatar-inner .avatar-img {
    width: 100%;
    height: 100%;
    border-radius: inherit;
    object-fit: cover;
    display: block;
}

.user-avatar-inner.offline {
    background: color-mix(in srgb, var(--surface-solid) 78%, #3f4f73);
    color: var(--text-muted);
    box-shadow: none;
}

.user-info {
    min-width: 0;
    flex: 1;
}

.user-status.offline {
    color: #94a3b8;
}

.logout-btn {
    display: inline-flex;
    align-items: center;
    gap: 0;
}

.sidebar.collapsed .logout-btn {
    margin-left: 0;
}

.sidebar.collapsed .history-label {
    display: none;
}
</style>