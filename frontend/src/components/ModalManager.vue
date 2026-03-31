<template>
  <div>
    <Login v-if="ui.showLogin" />
  </div>
</template>

<script setup>
import Login from './Login.vue'
import { useUiStore } from '../store/index'
const ui = useUiStore()
</script>

