<template>
  <footer class="input-area">
    <div class="input-wrapper">
        <button class="btn-attach" type="button" title="附加文件" @click="toggleAttachMenu">
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
            @input="onInput"
            @keydown.enter="handleEnter"
            ></textarea>
        <button id="send" class="btn-send" title="发送消息" @click="handleClick">
            <svg viewBox="0 0 24 24">
                <path d="M2.01 21L23 12 2.01 3 2 10l15 2-15 2z" />
            </svg>
        </button>
    </div>
    <!-- 附加菜单 -->
    <Teleport to="body">
      <div v-if="attachMenuOpen" class="attach-overlay" @mousedown="attachMenuOpen = false">
        <div class="attach-menu" :style="attachMenuStyle" @mousedown.stop>
          <button class="attach-menu-item" type="button" @click="pickFromDB">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><ellipse cx="12" cy="5" rx="9" ry="3"></ellipse><path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path><path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path></svg>
            从数据库选择
          </button>
          <button class="attach-menu-item" type="button" @click="pickFromComputer">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
            从电脑选择
          </button>
        </div>
      </div>
    </Teleport>
    <!-- 分享好友选择弹窗 -->
    <Teleport to="body">
      <div v-if="showSharePicker" class="attach-overlay" @mousedown="closeSharePicker">
        <div class="share-picker" @mousedown.stop>
          <div class="share-picker-head">
            <span>分享给好友</span>
            <button class="share-picker-close" type="button" @click="closeSharePicker">×</button>
          </div>
          <div class="share-picker-body">
            <div v-if="friends.length === 0" class="share-empty">暂无好友</div>
            <div v-for="friend in friends" :key="friend.id || friend.userId" class="share-friend-row" @click="selectShareTarget(friend)">
              <div class="share-friend-avatar" :style="avatarStyleFor(friend)">{{ getAvatarText(friend) }}</div>
              <span class="share-friend-name">{{ getDisplayName(friend) }}</span>
            </div>
          </div>
          <div class="share-picker-foot">
            <button class="share-picker-cancel" type="button" @click="closeSharePicker">取消</button>
          </div>
        </div>
      </div>
    </Teleport>
    <!-- 分享确认弹窗 -->
    <Teleport to="body">
      <div v-if="showShareConfirm" class="attach-overlay" @mousedown="showShareConfirm = false">
        <div class="share-confirm" @mousedown.stop>
          <div class="share-confirm-icon">
            <svg viewBox="0 0 24 24" width="40" height="40" fill="none" stroke="var(--primary)" stroke-width="1.5"><circle cx="18" cy="5" r="3"></circle><circle cx="6" cy="12" r="3"></circle><circle cx="18" cy="19" r="3"></circle><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"></line><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"></line></svg>
          </div>
          <div class="share-confirm-text">
            确认将文件分享给 <strong>{{ shareTargetName }}</strong>？
          </div>
          <div class="share-confirm-actions">
            <button class="share-confirm-btn primary" @click="doShare">确认发送</button>
            <button class="share-confirm-btn cancel" @click="showShareConfirm = false">取消</button>
          </div>
        </div>
      </div>
    </Teleport>
  </footer>
</template>

<script setup>
import { ref, watch, nextTick, onUnmounted, computed } from 'vue'
import { authFetch, safeReadJson } from '../services/api'

const props = defineProps(['modelValue'])
const emit = defineEmits(['update:modelValue', 'send', 'share'])
const textareaRef = ref(null)
let heightRafId = 0

// ─── Attach menu ───
const attachMenuOpen = ref(false)
const attachMenuStyle = ref({})
const friends = ref([])
const showSharePicker = ref(false)
const showShareConfirm = ref(false)
const shareTarget = ref(null)
const shareTargetName = computed(() => {
  const t = shareTarget.value
  return t?.name || t?.nickname || t?.userName || t?.id || ''
})

function getDisplayName(user) {
  return user?.name || user?.nickname || user?.userName || user?.username || user?.id || '未知'
}
function hashText(input) {
  const text = String(input || '')
  let hash = 0
  for (let i = 0; i < text.length; i += 1) {
    hash = (hash << 5) - hash + text.charCodeAt(i)
    hash |= 0
  }
  return Math.abs(hash)
}
const gradientPool = ['linear-gradient(135deg,#cbd5e1,#94a3b8)','linear-gradient(135deg,#dbeafe,#93c5fd)','linear-gradient(135deg,#dcfce7,#86efac)','linear-gradient(135deg,#fef3c7,#fcd34d)']
function avatarStyleFor(user) {
  const index = hashText(getDisplayName(user)) % gradientPool.length
  return { background: gradientPool[index] }
}
function getAvatarText(user, len = 1) {
  return String(getDisplayName(user) || '?').substring(0, len).toUpperCase()
}
async function fetchFriends() {
  try {
    const res = await authFetch('/user/friend', { method: 'GET' }).then(r => safeReadJson(r))
    if (res && res.code === 200) friends.value = Array.isArray(res.data) ? res.data : []
  } catch { /* ignore */ }
}
function toggleAttachMenu(e) {
  if (attachMenuOpen.value) { attachMenuOpen.value = false; return }
  const rect = e.currentTarget.getBoundingClientRect()
  attachMenuStyle.value = { left: Math.round(rect.left) + 'px', bottom: Math.round(window.innerHeight - rect.top + 8) + 'px' }
  attachMenuOpen.value = true
}
function pickFromDB() { attachMenuOpen.value = false }
function pickFromComputer() { attachMenuOpen.value = false }
async function openSharePicker() {
  attachMenuOpen.value = false
  await fetchFriends()
  showSharePicker.value = true
}
function selectShareTarget(friend) {
  shareTarget.value = friend
  showSharePicker.value = false
  showShareConfirm.value = true
}
function closeSharePicker() { showSharePicker.value = false; shareTarget.value = null }
async function doShare() {
  const target = shareTarget.value
  if (!target) { showShareConfirm.value = false; return }
  const userId = target?.id || target?.userId || target?.uid
  if (!userId) { showShareConfirm.value = false; return }
  emit('share', { target, userId: String(userId) })
  showShareConfirm.value = false
  shareTarget.value = null
}

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

.share-picker {
  position: fixed; left: 50%; top: 50%;
  transform: translate(-50%, -50%);
  width: min(320px, 88vw);
  border-radius: 16px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 55%, transparent);
  background: var(--overlay-strong);
  box-shadow: var(--shadow-lg);
  overflow: hidden;
}
.share-picker-head {
  display: flex; align-items: center; justify-content: space-between;
  padding: 14px 16px 10px;
  font-size: 15px; font-weight: 600; color: var(--text-main);
  border-bottom: 1px solid color-mix(in srgb, var(--panel-border) 30%, transparent);
}
.share-picker-close { width: 28px; height: 28px; display: grid; place-items: center; border: none; border-radius: 50%; background: transparent; color: var(--text-muted); font-size: 18px; cursor: pointer; }
.share-picker-close:hover { background: var(--hover-bg); }
.share-picker-body { max-height: 320px; overflow-y: auto; padding: 8px 12px; }
.share-friend-row { display: flex; align-items: center; gap: 10px; padding: 10px 8px; border-radius: 10px; cursor: pointer; transition: background-color 0.12s; }
.share-friend-row:hover { background: var(--hover-bg); }
.share-friend-avatar { width: 36px; height: 36px; border-radius: 50%; display: grid; place-items: center; color: #fff; font-weight: 700; font-size: 12px; flex-shrink: 0; }
.share-friend-name { font-size: 14px; font-weight: 500; color: var(--text-main); }
.share-empty { text-align: center; padding: 30px 10px; color: var(--text-muted); font-size: 14px; }
.share-picker-foot { padding: 10px 16px; border-top: 1px solid color-mix(in srgb, var(--panel-border) 30%, transparent); display: flex; justify-content: center; }
.share-picker-cancel { padding: 8px 20px; border-radius: 8px; border: 1px solid color-mix(in srgb, var(--panel-border) 50%, transparent); background: transparent; color: var(--text-secondary); font-size: 13px; cursor: pointer; }
.share-picker-cancel:hover { background: var(--hover-bg); }

.share-confirm {
  position: fixed; left: 50%; top: 50%;
  transform: translate(-50%, -50%);
  width: min(300px, 80vw);
  border-radius: 16px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 55%, transparent);
  background: var(--overlay-strong);
  box-shadow: var(--shadow-lg);
  padding: 24px 20px 16px;
  display: flex; flex-direction: column;
  align-items: center; gap: 12px;
}
.share-confirm-icon { width: 56px; height: 56px; border-radius: 50%; display: grid; place-items: center; background: color-mix(in srgb, var(--primary) 8%, transparent); }
.share-confirm-text { text-align: center; font-size: 14px; color: var(--text-main); line-height: 1.5; }
.share-confirm-text strong { font-weight: 600; }
.share-confirm-actions { display: flex; gap: 8px; width: 100%; margin-top: 4px; }
.share-confirm-btn { flex: 1; padding: 10px; border-radius: 10px; border: none; font-size: 14px; font-weight: 600; cursor: pointer; }
.share-confirm-btn.primary { background: var(--primary); color: #fff; }
.share-confirm-btn.primary:hover { background: var(--primary-hover); }
.share-confirm-btn.cancel { background: transparent; border: 1px solid color-mix(in srgb, var(--panel-border) 50%, transparent); color: var(--text-secondary); }
.share-confirm-btn.cancel:hover { background: var(--hover-bg); }
</style>
