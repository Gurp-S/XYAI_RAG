<template>
  <div
    id="login-overlay"
    :class="{ permanent: !ui.currentUser, active: ui.showLogin }"
    @click.self="ui.currentUser && ui.closeLogin()"
  >
    <section class="login-stage">
      <div class="login-card" :class="`mode-${mode}`">
        <h1 class="login-title">
          {{ titleText }}
        </h1>

        <p v-if="helperText" class="login-helper" :style="{ '--delay': '0.20s' }">
          {{ helperText }}
        </p>

        <form class="login-form" novalidate @submit.prevent="submit">
          <div class="login-field" :style="{ '--delay': '0.16s' }">
            <input
              id="accountId"
              v-model="accountId"
              type="text"
              class="login-input"
              placeholder="Your user ID"
              autocomplete="username"
            >
            <svg
              class="login-field-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              stroke-linecap="round"
              stroke-linejoin="round"
              aria-hidden="true"
            >
              <path d="M20 21a8 8 0 0 0-16 0"></path>
              <circle cx="12" cy="7" r="4"></circle>
            </svg>
          </div>

          <div v-if="mode === 'login'" class="auth-stack">
            <div class="login-field password-field" :style="{ '--delay': '0.24s' }">
              <input
                id="password"
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                class="login-input"
                placeholder="Your password"
                autocomplete="current-password"
              >
              <button
                type="button"
                class="password-toggle"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                :aria-pressed="showPassword"
                @click="showPassword = !showPassword"
              >
                <svg
                  v-if="showPassword"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M3 3l18 18"></path>
                  <path d="M10.58 10.58A3 3 0 0 0 13.41 13.41"></path>
                  <path d="M9.88 5.09A10.94 10.94 0 0 1 12 5c5.5 0 9.5 4.5 10 7-0.24 1.22-1.06 2.74-2.42 4.09"></path>
                  <path d="M6.1 6.1C3.8 7.53 2.34 9.76 2 12c0.5 2.5 4.5 7 10 7 1.03 0 2.02-.14 2.95-.39"></path>
                </svg>
                <svg
                  v-else
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"></path>
                  <circle cx="12" cy="12" r="3"></circle>
                </svg>
              </button>
              <svg
                class="login-field-icon login-field-lock"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <rect x="3" y="11" width="18" height="10" rx="2"></rect>
                <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
              </svg>
            </div>
          </div>

          <div v-else-if="mode === 'register'" class="auth-stack">
            <div class="login-field password-field" :style="{ '--delay': '0.24s' }">
              <input
                id="registerPassword"
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                class="login-input"
                placeholder="Password"
                autocomplete="new-password"
              >
              <button
                type="button"
                class="password-toggle"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                :aria-pressed="showPassword"
                @click="showPassword = !showPassword"
              >
                <svg
                  v-if="showPassword"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M3 3l18 18"></path>
                  <path d="M10.58 10.58A3 3 0 0 0 13.41 13.41"></path>
                  <path d="M9.88 5.09A10.94 10.94 0 0 1 12 5c5.5 0 9.5 4.5 10 7-0.24 1.22-1.06 2.74-2.42 4.09"></path>
                  <path d="M6.1 6.1C3.8 7.53 2.34 9.76 2 12c0.5 2.5 4.5 7 10 7 1.03 0 2.02-.14 2.95-.39"></path>
                </svg>
                <svg
                  v-else
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"></path>
                  <circle cx="12" cy="12" r="3"></circle>
                </svg>
              </button>
              <svg
                class="login-field-icon login-field-lock"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <rect x="3" y="11" width="18" height="10" rx="2"></rect>
                <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
              </svg>
            </div>

            <div class="login-field password-field" :style="{ '--delay': '0.32s' }">
              <input
                id="confirmPassword"
                v-model="confirmPassword"
                :type="showPassword ? 'text' : 'password'"
                class="login-input"
                placeholder="Repeat password"
                autocomplete="new-password"
              >
              <button
                type="button"
                class="password-toggle"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                :aria-pressed="showPassword"
                @click="showPassword = !showPassword"
              >
                <svg
                  v-if="showPassword"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M3 3l18 18"></path>
                  <path d="M10.58 10.58A3 3 0 0 0 13.41 13.41"></path>
                  <path d="M9.88 5.09A10.94 10.94 0 0 1 12 5c5.5 0 9.5 4.5 10 7-0.24 1.22-1.06 2.74-2.42 4.09"></path>
                  <path d="M6.1 6.1C3.8 7.53 2.34 9.76 2 12c0.5 2.5 4.5 7 10 7 1.03 0 2.02-.14 2.95-.39"></path>
                </svg>
                <svg
                  v-else
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"></path>
                  <circle cx="12" cy="12" r="3"></circle>
                </svg>
              </button>
              <svg
                class="login-field-icon login-field-lock"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <rect x="3" y="11" width="18" height="10" rx="2"></rect>
                <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
              </svg>
            </div>

            <div class="register-grid">
              <div class="login-field" :style="{ '--delay': '0.40s' }">
                <input
                  id="name"
                  v-model="name"
                  type="text"
                  class="login-input"
                  placeholder="昵称（可选）"
                  autocomplete="name"
                >
              </div>
              <div class="login-field" :style="{ '--delay': '0.46s' }">
                <input
                  id="email"
                  v-model="email"
                  type="email"
                  class="login-input"
                  placeholder="邮箱（可选）"
                  autocomplete="email"
                >
              </div>
              <div class="login-field" :style="{ '--delay': '0.52s' }">
                <input
                  id="phone"
                  v-model="phone"
                  type="tel"
                  class="login-input"
                  placeholder="手机号（可选）"
                  autocomplete="tel"
                >
              </div>
            </div>
          </div>

          <div v-else class="auth-stack">
            <div class="login-field" :style="{ '--delay': '0.24s' }">
              <input
                id="resetEmail"
                v-model="email"
                type="email"
                class="login-input"
                placeholder="绑定邮箱（可选）"
                autocomplete="email"
              >
              <svg
                class="login-field-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path d="M4 4h16v16H4z"></path>
                <path d="m4 6 8 6 8-6"></path>
              </svg>
            </div>

            <div class="login-field" :style="{ '--delay': '0.32s' }">
              <input
                id="resetPhone"
                v-model="phone"
                type="tel"
                class="login-input"
                placeholder="绑定手机号（可选）"
                autocomplete="tel"
              >
              <svg
                class="login-field-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <rect x="7" y="2" width="10" height="20" rx="2"></rect>
                <line x1="11" y1="18" x2="13" y2="18"></line>
              </svg>
            </div>

            <div class="login-field password-field" :style="{ '--delay': '0.40s' }">
              <input
                id="resetPassword"
                v-model="password"
                :type="showPassword ? 'text' : 'password'"
                class="login-input"
                placeholder="New password"
                autocomplete="new-password"
              >
              <button
                type="button"
                class="password-toggle"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                :aria-pressed="showPassword"
                @click="showPassword = !showPassword"
              >
                <svg
                  v-if="showPassword"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M3 3l18 18"></path>
                  <path d="M10.58 10.58A3 3 0 0 0 13.41 13.41"></path>
                  <path d="M9.88 5.09A10.94 10.94 0 0 1 12 5c5.5 0 9.5 4.5 10 7-0.24 1.22-1.06 2.74-2.42 4.09"></path>
                  <path d="M6.1 6.1C3.8 7.53 2.34 9.76 2 12c0.5 2.5 4.5 7 10 7 1.03 0 2.02-.14 2.95-.39"></path>
                </svg>
                <svg
                  v-else
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"></path>
                  <circle cx="12" cy="12" r="3"></circle>
                </svg>
              </button>
              <svg
                class="login-field-icon login-field-lock"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <rect x="3" y="11" width="18" height="10" rx="2"></rect>
                <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
              </svg>
            </div>

            <div class="login-field password-field" :style="{ '--delay': '0.48s' }">
              <input
                id="resetConfirmPassword"
                v-model="confirmPassword"
                :type="showPassword ? 'text' : 'password'"
                class="login-input"
                placeholder="Repeat new password"
                autocomplete="new-password"
              >
              <button
                type="button"
                class="password-toggle"
                :aria-label="showPassword ? '隐藏密码' : '显示密码'"
                :aria-pressed="showPassword"
                @click="showPassword = !showPassword"
              >
                <svg
                  v-if="showPassword"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M3 3l18 18"></path>
                  <path d="M10.58 10.58A3 3 0 0 0 13.41 13.41"></path>
                  <path d="M9.88 5.09A10.94 10.94 0 0 1 12 5c5.5 0 9.5 4.5 10 7-0.24 1.22-1.06 2.74-2.42 4.09"></path>
                  <path d="M6.1 6.1C3.8 7.53 2.34 9.76 2 12c0.5 2.5 4.5 7 10 7 1.03 0 2.02-.14 2.95-.39"></path>
                </svg>
                <svg
                  v-else
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  stroke-width="2"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  aria-hidden="true"
                >
                  <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7z"></path>
                  <circle cx="12" cy="12" r="3"></circle>
                </svg>
              </button>
              <svg
                class="login-field-icon login-field-lock"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="2"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <rect x="3" y="11" width="18" height="10" rx="2"></rect>
                <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
              </svg>
            </div>
          </div>

          <div v-if="mode === 'login'" class="login-meta" :style="{ '--delay': '0.34s' }">
            <label class="remember-row">
              <input v-model="rememberMe" type="checkbox">
              <span>Remember me</span>
            </label>
            <button type="button" class="forgot-link" @click="switchMode('reset')">
              忘记密码
            </button>
          </div>

          <button
            id="loginButton"
            class="login-submit"
            type="submit"
            :disabled="loading"
            :style="{ '--delay': '0.52s' }"
          >
            {{ submitText }}
          </button>

          <p class="login-switch-hint" :style="{ '--delay': '0.60s' }">
            <template v-if="mode === 'login'">
              没有账号？
              <button type="button" class="inline-link" @click="switchMode('register')">立即注册</button>
            </template>
            <template v-else-if="mode === 'register'">
              已有账号？
              <button type="button" class="inline-link" @click="switchMode('login')">返回登录</button>
            </template>
            <template v-else>
              已经记起密码？
              <button type="button" class="inline-link" @click="switchMode('login')">返回登录</button>
            </template>
          </p>

          <p v-show="success" class="login-success" aria-live="polite">{{ success }}</p>
          <p v-show="error" id="loginError" class="login-error" role="alert" aria-live="polite">{{ error }}</p>
        </form>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useUiStore } from '../store/index'
import { safeReadJson } from '../services/api'

const ui = useUiStore()
const mode = ref('login')
const accountId = ref('')
const password = ref('')
const confirmPassword = ref('')
const name = ref('')
const email = ref('')
const phone = ref('')
const rememberMe = ref(true)
const showPassword = ref(false)
const loading = ref(false)
const error = ref('')
const success = ref('')

const titleText = computed(() => {
  if (mode.value === 'register') return 'CREATE ACCOUNT'
  if (mode.value === 'reset') return 'RESET PASSWORD'
  return 'WELCOME'
})

const helperText = computed(() => {
  if (mode.value === 'register') {
    return '请使用数字用户 ID 创建账号，邮箱和手机号均可选。'
  }
  if (mode.value === 'reset') {
    return '请输入绑定邮箱或手机号验证身份，然后设置新密码。'
  }
  return ''
})

const submitText = computed(() => {
  if (loading.value) {
    if (mode.value === 'register') return '注册中...'
    if (mode.value === 'reset') return '重置中...'
    return '登录中...'
  }

  if (mode.value === 'register') return 'REGISTER'
  if (mode.value === 'reset') return 'RESET PASSWORD'
  return 'LOGIN'
})

onMounted(() => {
  const remembered = localStorage.getItem('rememberedLoginUser')
  if (remembered) {
    accountId.value = remembered
    rememberMe.value = true
  }
})

function switchMode(nextMode) {
  const normalized = ['login', 'register', 'reset'].includes(nextMode) ? nextMode : 'login'
  mode.value = normalized
  error.value = ''
  success.value = ''
  showPassword.value = false
  password.value = ''
  confirmPassword.value = ''
  name.value = ''
  email.value = ''
  phone.value = ''
}

function validateAccountId() {
  const id = String(accountId.value || '').trim()
  if (!id) {
    error.value = '用户 ID 不能为空'
    return ''
  }

  if (!/^\d+$/.test(id)) {
    error.value = '用户 ID 必须为数字'
    return ''
  }

  return id
}

async function submit() {
  if (mode.value === 'register') {
    await submitRegister()
    return
  }

  if (mode.value === 'reset') {
    await submitResetPassword()
    return
  }

  await submitLogin()
}

async function submitLogin() {
  const id = validateAccountId()
  if (!id) return

  if (!password.value) {
    error.value = '账号和密码不能为空'
    return
  }

  loading.value = true
  error.value = ''
  success.value = ''

  try {
    const formData = new FormData()
    formData.append('id', id)
    formData.append('password', password.value)

    const response = await fetch('/user/login', {
      method: 'POST',
      credentials: 'include',
      body: formData,
    })

    const result = await safeReadJson(response)
    if (!response.ok || !result || result.code !== 200) {
      error.value = result?.msg || '登录失败'
      return
    }

    const applied = await ui.applyLoginSession(result?.data?.accessToken, id)
    if (!applied) {
      error.value = '登录成功但会话建立失败，请重试'
      return
    }

    if (rememberMe.value) {
      localStorage.setItem('rememberedLoginUser', id)
    } else {
      localStorage.removeItem('rememberedLoginUser')
    }
  } catch (err) {
    error.value = '连接服务器失败'
    console.error('[Login] 请求失败:', err)
  } finally {
    loading.value = false
  }
}

async function submitRegister() {
  const id = validateAccountId()
  if (!id) return

  if (!password.value) {
    error.value = '密码不能为空'
    return
  }

  if (password.value.length < 6) {
    error.value = '密码长度至少 6 位'
    return
  }

  if (password.value !== confirmPassword.value) {
    error.value = '两次输入的密码不一致'
    return
  }

  loading.value = true
  error.value = ''
  success.value = ''

  try {
    const formData = new FormData()
    formData.append('id', id)
    formData.append('password', password.value)

    if (name.value.trim()) {
      formData.append('name', name.value.trim())
    }
    if (email.value.trim()) {
      formData.append('email', email.value.trim())
    }
    if (phone.value.trim()) {
      formData.append('phone', phone.value.trim())
    }

    const response = await fetch('/user/registry', {
      method: 'POST',
      credentials: 'include',
      body: formData,
    })

    const result = await safeReadJson(response)
    if (!response.ok || !result || result.code !== 200) {
      error.value = result?.msg || '注册失败'
      return
    }

    accountId.value = id
    switchMode('login')
    success.value = '注册成功，请使用该账号登录'
  } catch (err) {
    error.value = '连接服务器失败'
    console.error('[Login] 请求失败:', err)
  } finally {
    loading.value = false
  }
}

async function submitResetPassword() {
  const id = validateAccountId()
  if (!id) return

  const nextPassword = String(password.value || '')
  const nextConfirmPassword = String(confirmPassword.value || '')
  const nextEmail = String(email.value || '').trim()
  const nextPhone = String(phone.value || '').trim()

  if (!nextEmail && !nextPhone) {
    error.value = '请填写绑定邮箱或手机号'
    return
  }

  if (!nextPassword) {
    error.value = '新密码不能为空'
    return
  }

  if (nextPassword.length < 6) {
    error.value = '密码长度至少 6 位'
    return
  }

  if (nextPassword !== nextConfirmPassword) {
    error.value = '两次输入的新密码不一致'
    return
  }

  loading.value = true
  error.value = ''
  success.value = ''

  try {
    const formData = new FormData()
    formData.append('id', id)
    formData.append('password', nextPassword)
    formData.append('confirmPassword', nextConfirmPassword)

    if (nextEmail) {
      formData.append('email', nextEmail)
    }
    if (nextPhone) {
      formData.append('phone', nextPhone)
    }

    const response = await fetch('/user/reset-password', {
      method: 'POST',
      credentials: 'include',
      body: formData,
    })

    const result = await safeReadJson(response)
    if (!response.ok || !result || result.code !== 200) {
      error.value = result?.msg || '密码重置失败'
      return
    }

    switchMode('login')
    accountId.value = id
    success.value = '密码重置成功，请使用新密码登录'
  } catch (err) {
    error.value = '连接服务器失败'
    console.error('[Login] 请求失败:', err)
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
#login-overlay {
  position: fixed;
  inset: 0;
  z-index: 99999;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  background:
    linear-gradient(180deg, rgba(8, 12, 18, 0.34), rgba(8, 12, 18, 0.66)),
    #0d1220;
  opacity: 0;
  visibility: hidden;
  pointer-events: none;
  transition: opacity 180ms ease;
}

#login-overlay::before {
  content: '';
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(180deg, rgba(8, 12, 18, 0.10), rgba(8, 12, 18, 0.64)),
    url('/login/back.jpg');
  background-position: center;
  background-size: cover;
  background-repeat: no-repeat;
  transform: scale(1.03);
  filter: saturate(0.96) contrast(1.03);
  opacity: 0.96;
}

#login-overlay::after {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 50% 45%, rgba(255, 255, 255, 0.06), transparent 36%),
    linear-gradient(180deg, rgba(0, 0, 0, 0.04), rgba(0, 0, 0, 0.26));
}

#login-overlay.permanent,
#login-overlay.active {
  opacity: 1;
  visibility: visible;
  pointer-events: auto;
}

.login-stage {
  position: relative;
  z-index: 1;
  width: 100%;
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
}

.login-card {
  position: relative;
  width: min(496px, calc(100vw - 24px));
  padding: 32px 30px 26px;
  border-radius: 26px;
  border: 1px solid rgba(255, 255, 255, 0.28);
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.13), rgba(255, 255, 255, 0.055)),
    rgba(12, 16, 26, 0.34);
  backdrop-filter: blur(16px) saturate(122%);
  -webkit-backdrop-filter: blur(16px) saturate(122%);
  box-shadow:
    0 24px 48px rgba(0, 0, 0, 0.30),
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
  overflow: hidden;
  animation: cardRise 420ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.login-card::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(circle at 86% -10%, rgba(255, 255, 255, 0.10), transparent 30%),
    radial-gradient(circle at 8% 108%, rgba(96, 165, 250, 0.09), transparent 34%),
    radial-gradient(circle at 100% 100%, rgba(167, 139, 250, 0.07), transparent 28%);
}

.login-title,
.login-helper,
.login-form {
  position: relative;
  z-index: 1;
}

.login-title {
  margin: 0;
  color: #f8fafc;
  font-size: clamp(2.08rem, 3.85vw, 2.58rem);
  line-height: 1;
  letter-spacing: 0.12em;
  font-weight: 680;
  animation: riseIn 520ms cubic-bezier(0.22, 1, 0.36, 1) both;
  animation-delay: 0.12s;
}

.login-helper {
  margin: 16px 0 20px;
  max-width: 28ch;
  color: rgba(248, 250, 252, 0.75);
  font-size: 0.94rem;
  line-height: 1.55;
  animation: riseIn 480ms cubic-bezier(0.22, 1, 0.36, 1) both;
}

.login-form {
  display: grid;
  gap: 14px;
  margin-top: 10px;
}

.login-helper + .login-form {
  margin-top: 0;
}

.auth-stack,
.register-grid {
  display: grid;
  gap: 14px;
}

.login-field {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 62px;
  padding: 0 18px 0 20px;
  border-radius: 19px;
  border: 1px solid rgba(255, 255, 255, 0.52);
  background: rgba(255, 255, 255, 0.08);
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.05),
    0 8px 18px rgba(2, 6, 23, 0.12);
  animation: riseIn 420ms cubic-bezier(0.22, 1, 0.36, 1) both;
  animation-delay: var(--delay, 0.28s);
}

.login-field:focus-within {
  border-color: rgba(255, 255, 255, 0.92);
  box-shadow:
    0 0 0 3px rgba(255, 255, 255, 0.12),
    0 12px 22px rgba(2, 6, 23, 0.14);
}

.login-input {
  width: 100%;
  min-width: 0;
  border: none;
  background: transparent;
  color: #fff;
  font-size: 1.05rem;
  line-height: 1.36;
  padding: 0;
  outline: none;
}

.password-field {
  padding-right: 14px;
}

.password-toggle {
  flex-shrink: 0;
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 999px;
  background: transparent;
  color: rgba(255, 255, 255, 0.92);
  padding: 0;
  cursor: pointer;
  transition:
    background-color 0.18s ease,
    color 0.18s ease,
    transform 0.18s ease;
}

.password-toggle svg {
  width: 17px;
  height: 17px;
}

.password-toggle:hover {
  background: rgba(255, 255, 255, 0.12);
  color: #ffffff;
  transform: translateY(-1px);
}

.password-toggle:focus-visible {
  outline: 2px solid rgba(255, 255, 255, 0.72);
  outline-offset: 2px;
}

.login-input::placeholder {
  color: rgba(255, 255, 255, 0.74);
}

.login-input:-webkit-autofill,
.login-input:-webkit-autofill:hover,
.login-input:-webkit-autofill:focus,
.login-input:-webkit-autofill:active {
  -webkit-text-fill-color: #ffffff;
  caret-color: #ffffff;
  -webkit-box-shadow: 0 0 0 1000px rgba(255, 255, 255, 0.08) inset;
  box-shadow: 0 0 0 1000px rgba(255, 255, 255, 0.08) inset;
  transition: background-color 9999s ease-out 0s;
}

.login-field-icon {
  flex-shrink: 0;
  width: 17px;
  height: 17px;
  color: rgba(255, 255, 255, 0.82);
}

.login-field-lock {
  width: 18px;
  height: 18px;
  color: rgba(255, 255, 255, 0.84);
}

.login-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  margin-top: 4px;
  animation: riseIn 440ms cubic-bezier(0.22, 1, 0.36, 1) both;
  animation-delay: var(--delay, 0.46s);
}

.remember-row {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.86);
  font-size: 0.95rem;
  user-select: none;
}

.remember-row input {
  accent-color: #ffffff;
}

.forgot-link,
.inline-link {
  border: none;
  background: transparent;
  color: rgba(255, 255, 255, 0.92);
  font-size: 0.94rem;
  cursor: pointer;
  text-decoration: none;
}

.forgot-link:hover,
.inline-link:hover {
  text-decoration: underline;
  text-underline-offset: 2px;
}

.login-switch-hint {
  margin: 4px 0 0;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 6px;
  color: rgba(255, 255, 255, 0.80);
  font-size: 0.9rem;
  line-height: 1.4;
  text-align: center;
  animation: riseIn 440ms cubic-bezier(0.22, 1, 0.36, 1) both;
  animation-delay: var(--delay, 0.92s);
}

.login-submit {
  width: 100%;
  min-height: 60px;
  border: none;
  border-radius: 999px;
  background: #ffffff;
  color: #111827;
  font-size: 1.06rem;
  font-weight: 700;
  letter-spacing: 0.12em;
  cursor: pointer;
  box-shadow: 0 14px 24px rgba(2, 6, 23, 0.16);
  animation: riseIn 440ms cubic-bezier(0.22, 1, 0.36, 1) both;
  animation-delay: var(--delay, 0.84s);
  transition:
    transform 0.18s ease,
    background-color 0.18s ease,
    box-shadow 0.18s ease,
    opacity 0.18s ease;
}

.login-submit:hover:not(:disabled) {
  background: #e5e7eb;
  transform: translateY(-1px);
  box-shadow: 0 16px 28px rgba(2, 6, 23, 0.18);
}

.login-submit:disabled {
  opacity: 0.72;
  cursor: not-allowed;
  transform: none;
}

.login-success,
.login-error {
  margin: 0;
  border-radius: 14px;
  padding: 8px 10px;
  font-size: 0.9rem;
  line-height: 1.5;
}

.login-success {
  border: 1px solid rgba(110, 231, 183, 0.32);
  background: rgba(16, 185, 129, 0.14);
  color: #dcfce7;
}

.login-error {
  border: 1px solid rgba(248, 113, 113, 0.36);
  background: rgba(127, 29, 29, 0.28);
  color: #fee2e2;
}

@keyframes cardRise {
  from {
    opacity: 0;
    transform: translate3d(0, 14px, 0) scale(0.99);
  }
  to {
    opacity: 1;
    transform: translate3d(0, 0, 0) scale(1);
  }
}

@keyframes riseIn {
  from {
    opacity: 0;
    transform: translate3d(0, 12px, 0);
  }
  to {
    opacity: 1;
    transform: translate3d(0, 0, 0);
  }
}

@media (max-width: 640px) {
  .login-stage {
    padding: 14px;
    align-items: flex-start;
    overflow-y: auto;
  }

  .login-card {
    width: min(456px, calc(100vw - 18px));
    margin: 10px auto 18px;
    min-height: 0;
    padding: 24px 18px 20px;
    border-radius: 22px;
  }

  .login-title {
    font-size: clamp(1.92rem, 9.4vw, 2.28rem);
  }

  .login-helper {
    margin-bottom: 14px;
  }

  .login-form,
  .auth-stack,
  .register-grid {
    gap: 12px;
  }

  .login-field {
    min-height: 54px;
    padding-inline: 14px;
    border-radius: 15px;
  }

  .password-field {
    padding-right: 10px;
  }

  .password-toggle {
    width: 28px;
    height: 28px;
  }

  .login-meta {
    flex-direction: column;
    align-items: flex-start;
  }

  .login-submit {
    min-height: 56px;
  }
}

@media (prefers-reduced-motion: reduce) {
  #login-overlay,
  .login-card,
  .login-title,
  .login-helper,
  .login-field,
  .login-meta,
  .login-switch-hint,
  .login-submit {
    animation: none !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
