<template>
  <div class="app" :class="themeClass">
    <Sidebar />
    <router-view />
    <ModalManager />
  </div>
</template>

<script setup>
import Sidebar from './components/Sidebar.vue'
import ModalManager from './components/ModalManager.vue'
import { useUiStore } from './store/index'
import { computed } from 'vue'
const ui = useUiStore()
const themeClass = computed(() => ui.currentUser ? 'dark' : 'light')
</script>

<style>
.app{display:flex;height:100vh;width:100vw}
</style>

