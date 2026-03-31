<template>
  <header class="header">
    <div class="header-left">
      <button class="sidebar-toggle" @click="$emit('toggleSidebar')">☰</button>
      <div class="header-title">对话主控台 <span class="enterprise-badge">ENTERPRISE</span></div>
    </div>
    <div class="header-controls">
      <div class="status-badge"><span class="status-dot"></span> 系统就绪</div>
      <button class="theme-toggle" @click="$emit('toggleTheme')">切换主题</button>
    </div>
  </header>
</template>

<script setup>
</script>

<style scoped>
.header{display:flex;justify-content:space-between;align-items:center}
.header-left{display:flex;align-items:center;gap:12px}
.enterprise-badge{margin-left:8px}
.sidebar-toggle{border:none;background:transparent;cursor:pointer}
.theme-toggle{border:none;background:transparent;cursor:pointer}
</style>

