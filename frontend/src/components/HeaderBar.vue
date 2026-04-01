<template>
  <header class="header">
    <div class="header-title">
        <button class="sidebar-toggle" id="toggleSidebar" title="收起/展开侧边栏"
            style="margin-right: 10px; border: none; width: auto; padding: 0;" @click="store.toggleSidebar()">
            <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="3" y1="12" x2="21" y2="12"></line>
                <line x1="3" y1="6" x2="21" y2="6"></line>
                <line x1="3" y1="18" x2="21" y2="18"></line>
            </svg>
        </button>
        对话主控制台
        <span class="enterprise-badge">ENTERPRISE</span>
    </div>
    <div class="header-controls">
        <div class="status-badge">
            <div class="status-dot"></div> <span id="status">系统就绪</span>
        </div>
        <button class="theme-toggle" id="toggleTheme" title="切换深色/浅色模式" @click="toggleThemeLocally">
            <svg id="moonIcon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                stroke-width="2" stroke-linecap="round" stroke-linejoin="round" :style="{ display: isDark ? 'none' : 'block' }">
                <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>
            </svg>
            <svg id="sunIcon" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                stroke-width="2" stroke-linecap="round" stroke-linejoin="round" :style="{ display: isDark ? 'block' : 'none' }">
                <circle cx="12" cy="12" r="5"></circle>
                <line x1="12" y1="1" x2="12" y2="3"></line>
                <line x1="12" y1="21" x2="12" y2="23"></line>
                <line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line>
                <line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line>
                <line x1="1" y1="12" x2="3" y2="12"></line>
                <line x1="21" y1="12" x2="23" y2="12"></line>
                <line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line>
                <line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line>
            </svg>
        </button>
        <button id="newConversation" class="btn-ghost" type="button" title="开启新会话">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="12" y1="5" x2="12" y2="19"></line>
                <line x1="5" y1="12" x2="19" y2="12"></line>
            </svg>
            新对话
        </button>
    </div>
  </header>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useUiStore } from '../store/index'

const store = useUiStore()
const isDark = ref(document.body.classList.contains('dark'))

function toggleThemeLocally() {
    const willBeDark = !document.body.classList.contains('dark')
    if (willBeDark) {
        document.body.classList.add('dark')
        localStorage.setItem('theme', 'dark')
    } else {
        document.body.classList.remove('dark')
        localStorage.setItem('theme', 'light')
    }
    isDark.value = willBeDark
}

onMounted(() => {
    const observer = new MutationObserver(() => {
        isDark.value = document.body.classList.contains('dark')
    })
    observer.observe(document.body, { attributes: true, attributeFilter: ['class'] })
})
</script>

<style scoped>
</style>