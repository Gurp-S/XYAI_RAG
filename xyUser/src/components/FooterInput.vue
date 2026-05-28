<template>
  <footer class="input-area">
    <div class="input-wrapper">
        <button class="btn-attach" type="button" @click="toggleAttachMenu">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
                <line x1="12" y1="5" x2="12" y2="19"></line>
                <line x1="5" y1="12" x2="19" y2="12"></line>
            </svg>
        </button>
        <label class="sr-only" for="message">消息输入</label>
        <textarea
id="message" ref="textareaRef"
            rows="1"
            placeholder="输入你想咨询的问题，或要求查询企业知识库... (按 Enter 发送，Shift+Enter 换行)"
            :value="modelValue"
            :disabled="disabled"
            @input="onInput"
            @keydown.enter="handleEnter"
            ></textarea>
        <button id="send" class="btn-send" :disabled="disabled" @click="handleClick">
            <svg viewBox="0 0 24 24">
                <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" />
            </svg>
        </button>
    </div>
    <!-- 附加菜单 -->
    <Teleport to="body">
      <div v-if="attachMenuOpen" class="attach-overlay" @mousedown="attachMenuOpen = false">
        <div class="attach-menu" :style="attachMenuStyle" @mousedown.stop>

          <!-- AI 聊天模式：解析文件 -->
          <template v-if="chatMode === 'ai'">
            <button class="attach-menu-item" type="button" @click="pickFromDB">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><ellipse cx="12" cy="5" rx="9" ry="3"></ellipse><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path></svg>
              从数据库选择
            </button>
            <button class="attach-menu-item" type="button" @click="pickFromComputer">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
              从电脑选择
            </button>
          </template>

          <!-- 好友/群聊聊天模式：分享文件 -->
          <template v-else>
            <button class="attach-menu-item" type="button" @click="pickFromDB">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><ellipse cx="12" cy="5" rx="9" ry="3"></ellipse><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path></svg>
              从数据库选择
            </button>
            <button class="attach-menu-item" type="button" @click="pickLocalFile">
              <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
              分享本地文件
            </button>
          </template>

        </div>
      </div>
    </Teleport>
  </footer>
</template>

<script setup>
import { ref, watch, nextTick, onUnmounted } from 'vue'

const props = defineProps({
  modelValue: {
    type: String,
    default: '',
  },
  disabled: {
    type: Boolean,
    default: false,
  },
  chatMode: {
    type: String,
    default: 'ai',
  },
})
const emit = defineEmits(['update:modelValue', 'send', 'pick-db', 'pick-computer', 'pick-local-file'])
const textareaRef = ref(null)
let heightRafId = 0

// ─── Attach menu ───
const attachMenuOpen = ref(false)
const attachMenuStyle = ref({})
function toggleAttachMenu(e) {
  if (attachMenuOpen.value) { attachMenuOpen.value = false; return }
  const rect = e.currentTarget.getBoundingClientRect()
  attachMenuStyle.value = { left: Math.round(rect.left) + 'px', bottom: Math.round(window.innerHeight - rect.top + 8) + 'px' }
  attachMenuOpen.value = true
}
function pickFromDB() { attachMenuOpen.value = false; emit('pick-db') }
function pickFromComputer() { attachMenuOpen.value = false; emit('pick-computer') }
function pickLocalFile() { attachMenuOpen.value = false; emit('pick-local-file') }

// ─── Textarea auto-height ───
function scheduleAdjustHeight() {
  if (heightRafId) return
  heightRafId = requestAnimationFrame(() => {
    heightRafId = 0
    const el = textareaRef.value
    if (!el) return
    el.style.height = 'auto'
    if (el.value) {
      const maxHeight = 200
      const targetHeight = Math.min(el.scrollHeight, maxHeight)
      el.style.height = targetHeight + 'px'
      el.style.overflowY = el.scrollHeight > maxHeight ? 'auto' : 'hidden'
    } else {
      el.style.overflowY = 'hidden'
    }
  })
}
function adjustHeight() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  if (el.value) {
    const maxHeight = 200
    const targetHeight = Math.min(el.scrollHeight, maxHeight)
    el.style.height = targetHeight + 'px'
    el.style.overflowY = el.scrollHeight > maxHeight ? 'auto' : 'hidden'
  } else {
    el.style.overflowY = 'hidden'
  }
}
onUnmounted(() => { if (heightRafId) { cancelAnimationFrame(heightRafId); heightRafId = 0 } })
watch(() => props.modelValue, (newVal) => { if (newVal === '') { nextTick(() => adjustHeight()) } })
function onInput(e) { emit('update:modelValue', e.target.value); scheduleAdjustHeight() }
function handleEnter(e) {
  if (!e.shiftKey) { e.preventDefault(); emit('send'); setTimeout(() => adjustHeight(), 0) }
}
function handleClick() {
  try { emit('send') } catch (err) { /* ignore */ }
  nextTick(() => { try { const el = textareaRef.value; if (el && typeof el.focus === 'function') el.focus() } catch (err) { /* ignore */ } })
}
</script>

<style scoped>
.btn-attach {
  width: 36px; height: 36px;
  display: grid; place-items: center;
  border: none; border-radius: 10px;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer; flex-shrink: 0;
  transition: background-color 0.12s, color 0.12s;
}
.btn-attach:hover { background: var(--hover-bg); color: var(--text-main); }
.btn-attach svg { transition: transform 0.2s; }
.btn-attach:hover svg { transform: rotate(90deg); }

.attach-overlay { position: fixed; inset: 0; z-index: 100000; background: transparent; }
.attach-menu {
  position: fixed; min-width: 180px; padding: 6px;
  border-radius: 14px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 55%, transparent);
  background: color-mix(in srgb, var(--overlay-strong) 96%, transparent);
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(14px); -webkit-backdrop-filter: blur(14px);
}
.attach-menu-item {
  width: 100%; display: flex; align-items: center; gap: 10px;
  padding: 10px 12px; border-radius: 10px;
  border: none; background: transparent;
  color: var(--text-main); font-size: 14px; font-weight: 500;
  cursor: pointer; transition: background-color 0.12s;
}
.attach-menu-item:hover { background: var(--hover-bg); }
.attach-menu-item svg { flex-shrink: 0; opacity: 0.7; }
.attach-menu-divider { height: 1px; margin: 4px 8px; background: color-mix(in srgb, var(--panel-border) 40%, transparent); }
</style>
