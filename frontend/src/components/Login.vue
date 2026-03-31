<template>
  <div class="modal-overlay" @click.self="close">
    <div class="modal">
      <div class="modal-header">
        <h3>登录</h3>
        <button class="modal-close" @click="close">✕</button>
      </div>
      <div class="modal-body">
        <label>用户名</label>
        <input v-model="username" />
        <label>密码</label>
        <input type="password" v-model="password" />
      </div>
      <div class="modal-footer">
        <button @click="submit" class="btn-send">登录</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useUiStore } from '../store/index'

const ui = useUiStore()
const username = ref('')
const password = ref('')

function close() {
  ui.closeLogin()
}

function submit() {
  ui.setUser({ name: username.value || 'Admin User' })
  ui.closeLogin()
}
</script>

<style scoped>
.modal-overlay{position:fixed;inset:0;background:rgba(0,0,0,0.45);display:flex;align-items:center;justify-content:center;z-index:1000}
.modal{background:#fff;padding:18px;border-radius:12px;min-width:320px}
.modal-header{display:flex;align-items:center;justify-content:space-between}
.modal-body{display:flex;flex-direction:column;gap:8px;margin-top:12px}
.modal-footer{display:flex;justify-content:flex-end;margin-top:12px}
.modal-close{background:transparent;border:none;font-size:18px}
input{padding:8px;border-radius:6px;border:1px solid #ddd}
</style>

