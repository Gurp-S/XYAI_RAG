<template>
  <div class="login-page">
    <form class="login-card" @submit.prevent="onSubmit">
      <h1 class="login-card__title">XYAI Admin</h1>
      <p class="login-card__hint">使用管理员账号登录（userRank = 0/1）</p>

      <label class="field">
        <span>用户 ID</span>
        <input v-model="userId" type="text" autocomplete="username" required placeholder="例如 1" />
      </label>

      <label class="field">
        <span>密码</span>
        <input v-model="password" type="password" autocomplete="current-password" required />
      </label>

      <button class="submit" type="submit" :disabled="loading">
        {{ loading ? '登录中…' : '登录' }}
      </button>
    </form>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { adminLogin } from '../api/auth'
import { setAccessToken } from '../utils/auth'

const router = useRouter()
const route = useRoute()
const userId = ref('')
const password = ref('')
const loading = ref(false)

async function onSubmit() {
  loading.value = true
  try {
    const data = await adminLogin(userId.value.trim(), password.value)
    if (!data?.accessToken) {
      throw new Error('未返回 accessToken')
    }
    setAccessToken(data.accessToken)
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.replace(redirect || '/dashboard')
  } catch (e) {
    ElMessage.error(e?.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  background:
    radial-gradient(1200px 600px at 10% -10%, rgba(37, 99, 235, 0.18), transparent 60%),
    radial-gradient(900px 500px at 100% 0%, rgba(14, 165, 233, 0.12), transparent 55%),
    linear-gradient(160deg, #0b1220 0%, #111827 45%, #0f172a 100%);
  color: #e5e7eb;
  font-family: "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
}

.login-card {
  width: min(400px, 92vw);
  padding: 2rem 1.75rem;
  border: 1px solid rgba(148, 163, 184, 0.25);
  background: rgba(15, 23, 42, 0.82);
  backdrop-filter: blur(10px);
  display: grid;
  gap: 1rem;
}

.login-card__title {
  margin: 0;
  font-size: 1.6rem;
  letter-spacing: 0.04em;
  font-weight: 650;
}

.login-card__hint {
  margin: 0 0 0.5rem;
  color: #94a3b8;
  font-size: 0.9rem;
}

.field {
  display: grid;
  gap: 0.4rem;
  font-size: 0.85rem;
  color: #cbd5e1;
}

.field input {
  height: 2.5rem;
  border: 1px solid rgba(148, 163, 184, 0.35);
  background: rgba(2, 6, 23, 0.55);
  color: #f8fafc;
  padding: 0 0.75rem;
  outline: none;
}

.field input:focus {
  border-color: #38bdf8;
}

.submit {
  margin-top: 0.5rem;
  height: 2.6rem;
  border: 0;
  background: linear-gradient(90deg, #0284c7, #2563eb);
  color: #fff;
  font-weight: 600;
  cursor: pointer;
}

.submit:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
</style>
