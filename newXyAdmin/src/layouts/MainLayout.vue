<template>
  <div class="layout" :class="{ 'layout--collapsed': sidebarCollapsed }">
    <!-- Sidebar -->
    <aside class="sidebar">
      <div class="sidebar__logo">
        <div class="sidebar__logo-icon">
          <el-icon :size="22"><Cpu /></el-icon>
        </div>
        <transition name="fade">
          <span v-show="!sidebarCollapsed" class="sidebar__logo-text">XYAI Admin</span>
        </transition>
      </div>

      <el-menu
        :default-active="route.path"
        :collapse="sidebarCollapsed"
        :collapse-transition="true"
        router
        class="sidebar__menu"
      >
        <el-menu-item v-for="item in menuItems" :key="item.path" :index="item.path">
          <el-icon class="menu-icon"><component :is="item.icon" /></el-icon>
          <template #title>
            <span>{{ item.title }}</span>
          </template>
        </el-menu-item>
      </el-menu>
    </aside>

    <!-- Main -->
    <div class="main-area">
      <!-- Header -->
      <header class="header">
        <div class="header__left">
          <el-button text @click="sidebarCollapsed = !sidebarCollapsed">
            <el-icon :size="20"><Fold v-if="!sidebarCollapsed" /><Expand v-else /></el-icon>
          </el-button>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="route.meta?.title">{{ route.meta.title }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header__right">
          <el-button text circle @click="toggleTheme" :title="isDark ? '切换日间模式' : '切换夜间模式'">
            <el-icon :size="18">
              <MoonNight v-if="isDark" />
              <Sunny v-else />
            </el-icon>
          </el-button>
        </div>
      </header>

      <!-- Content -->
      <main class="content">
        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRoute } from 'vue-router'
import { useTimeTheme } from '../styles/time-theme'

const route = useRoute()
const sidebarCollapsed = ref(false)
const { isDark, toggleTheme } = useTimeTheme()

const menuItems = computed(() => [
  { path: '/dashboard', title: '仪表盘', icon: 'Odometer' },
  { path: '/chat/tokens', title: 'Token 记录', icon: 'Coin' },
  { path: '/users', title: '用户管理', icon: 'User' },
  { path: '/llm', title: '模型管理', icon: 'Cpu' },
  { path: '/milvus', title: '向量库', icon: 'FolderOpened' },
  { path: '/milvus/stats', title: '物理统计', icon: 'DataAnalysis' },
  { path: '/evaluate', title: '评估管理', icon: 'List' },
  { path: '/etl/pipeline', title: 'ETL 流水线', icon: 'Refresh' },
  { path: '/mcp/tools', title: 'MCP 工具', icon: 'Tools' },
  { path: '/announcement', title: '公告管理', icon: 'Bell' },
  { path: '/intent/tree', title: '意图树', icon: 'Share' },
  { path: '/trace/info', title: '调用链追踪', icon: 'Search' }
])
</script>

<style scoped>
.layout {
  display: flex;
  height: 100vh;
  background: var(--bg-primary);
  transition: background-color 0.5s ease;
}

/* Sidebar */
.sidebar {
  width: var(--sidebar-width);
  background: var(--bg-sidebar);
  border-right: 1px solid var(--border-color);
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  transition: width 0.3s cubic-bezier(0.4, 0, 0.2, 1), background-color 0.5s ease;
  overflow: hidden;
  z-index: 10;
}

.layout--collapsed .sidebar {
  width: 64px;
}

.sidebar__logo {
  height: var(--header-height);
  display: flex;
  align-items: center;
  padding: 0 16px;
  gap: 10px;
  border-bottom: 1px solid var(--border-color);
  flex-shrink: 0;
}

.sidebar__logo-icon {
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--primary-lighter);
  border-radius: var(--radius-md);
  color: var(--primary);
  flex-shrink: 0;
}

.sidebar__logo-text {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  white-space: nowrap;
}

.sidebar__menu {
  flex: 1;
  overflow-y: auto;
  padding: 8px 0;
  border: none !important;
}

/* Fix el-menu-item icon alignment */
.sidebar__menu :deep(.el-menu-item) {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 42px;
  line-height: 42px;
  padding: 0 12px;
}

.sidebar__menu :deep(.el-menu-item .menu-icon) {
  width: 20px;
  height: 20px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  margin: 0;
  vertical-align: middle;
}

.sidebar__menu :deep(.el-menu-item.is-active) {
  background: var(--primary-lighter) !important;
  color: var(--primary) !important;
  font-weight: 500;
}

.sidebar__menu :deep(.el-menu-item:hover) {
  background: var(--bg-hover) !important;
}

/* Fix collapsed state icon centering */
.layout--collapsed .sidebar__menu :deep(.el-menu-item) {
  justify-content: center;
  padding: 0;
}

/* Main Area */
.main-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  min-width: 0;
}

/* Header */
.header {
  height: var(--header-height);
  background: var(--bg-header);
  border-bottom: 1px solid var(--border-color);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  flex-shrink: 0;
  transition: background-color 0.5s ease;
}

.header__left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.header__right {
  display: flex;
  align-items: center;
  gap: 12px;
}

/* Content */
.content {
  flex: 1;
  padding: 10px 20px;
  overflow: hidden;
  min-height: 0;
}

/* Transition */
.fade-enter-active, .fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from, .fade-leave-to {
  opacity: 0;
}
</style>
