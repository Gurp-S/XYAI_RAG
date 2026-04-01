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
        <div class="menu-item active" id="navChat" title="智能问答" @click="navigateTo('/')">
            <svg viewBox="0 0 24 24">
                <path
                    d="M21 11.5a8.38 8.38 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.38 8.38 0 0 1-3.8-.9L3 21l1.9-5.7a8.38 8.38 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.38 8.38 0 0 1 3.8-.9h.5a8.48 8.48 0 0 1 8 8v.5z">
                </path>
            </svg>
            <span>智能问答</span>
        </div>
        <div class="menu-item" id="navUpload" title="上传知识库" @click="store.openModal('upload')">
            <svg viewBox="0 0 24 24">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                <polyline points="17 8 12 3 7 8"></polyline>
                <line x1="12" y1="3" x2="12" y2="15"></line>
            </svg>
            <span>上传知识库</span>
        </div>
        <div class="menu-item" id="navDB" title="向量数据库" @click="store.openModal('db')">
            <svg viewBox="0 0 24 24">
                <ellipse cx="12" cy="5" rx="9" ry="3"></ellipse>
                <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path>
                <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path>
            </svg>
            <span>向量数据库</span>
        </div>
    </div>

    <div class="history-container">
        <div class="history-label">历史对话</div>
        <HistoryList />
    </div>

    <div class="user-profile" title="用户中心">
        <div class="user-avatar-img" style="background:#00e5ff;color:#fff;display:flex;align-items:center;justify-content:center;font-weight:bold;font-size:14px;border:none;">AU</div>
        <div class="user-info">
            <div class="user-name">Admin User</div>
            <div class="user-status">Online</div>
        </div>
    </div>
  </aside>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useUiStore } from '../store/index'
import HistoryList from './HistoryList.vue'

const store = useUiStore()
const router = useRouter()

function navigateTo(path) {
  router.push(path)
}
</script>

<style scoped>
</style>