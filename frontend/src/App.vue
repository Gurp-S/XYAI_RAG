<template>
  <div class="floating-bg"></div>
  <div class="app-container">
    <Sidebar />
    <router-view />
    <ModalManager />
  </div>
</template>

<script setup>
import Sidebar from './components/Sidebar.vue'
import ModalManager from './components/ModalManager.vue'
import { useUiStore } from './store/index'

const ui = useUiStore()
</script>

<style>
</style>
