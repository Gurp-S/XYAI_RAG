<template>
  <div id="login-overlay" v-if="ui.showLogin" @click.self="ui.closeLogin()">
    <div class="login-container">
        <div class="login-logo">XY</div>
        <div class="login-title">欢迎使用 XY-AI 智能体平台</div>
        <div class="login-subtitle">请登录以继续</div>

        <div class="login-field">
            <label for="username">用户名</label>
            <input type="text" id="username" class="login-input" placeholder="输入你的用户名" v-model="username">
        </div>

        <div class="login-field">
            <label for="password">密码</label>
            <input type="password" id="password" class="login-input" placeholder="输入你的密码" v-model="password" @keyup.enter="submit">
        </div>

        <button class="btn-ghost login-btn" id="loginButton" @click="submit" :disabled="loading">
            {{ loading ? '登录中...' : '登 录' }}
        </button>
        <div class="login-error" id="loginError" :style="{ display: error ? 'block' : 'none' }">{{ error }}</div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useUiStore } from '../store/index'

const ui = useUiStore()
const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  if (!username.value || !password.value) {
      error.value = '账号和密码不能为空'
      return
  }
  loading.value = true
  error.value = ''
  
  // mock real login logic
  setTimeout(() => {
      ui.setUser({ name: username.value || 'Admin User' })
      ui.closeLogin()
      loading.value = false
  }, 500)
}
</script>

<style scoped>
/* Scoped styles are managed globally in styles.css */
</style>
