<template>
  <div class="history-list-wrapper">
    <div v-if="!history || history.length === 0" class="history-empty">暂无历史记录</div>
    <div
      v-for="(h, i) in history"
      :key="h.conversationId || i"
      class="history-item"
      :class="{
        active: h.conversationId && h.conversationId === activeConversationId,
        'menu-open':
          menuOpen && menuSession?.conversationId && menuSession.conversationId === h.conversationId,
      }"
      :title="h.title || h.summaryText || '新对话'"
      @click="handleSelect(h)"
    >
      <svg class="history-item-icon" viewBox="0 0 24 24" width="14" height="14" stroke="currentColor" fill="none" stroke-width="2">
        <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
      </svg>
      <div class="history-item-body">
        <span class="history-item-text">{{ h.title || h.summaryText || '对话 ' + (i+1) }}</span>
        <span class="history-item-sub">{{ formatSubText(h) }}</span>
      </div>

      <button
        class="history-item-more"
        type="button"
        title="更多"
        @click.stop="toggleMenu($event, h)"
      >
        <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">
          <circle cx="5" cy="12" r="1.4"></circle>
          <circle cx="12" cy="12" r="1.4"></circle>
          <circle cx="19" cy="12" r="1.4"></circle>
        </svg>
      </button>
    </div>

    <teleport to="body">
      <div v-if="menuOpen" class="history-menu-overlay" @mousedown="closeMenu">
        <div
          ref="menuEl"
          class="history-menu"
          :style="{ left: menuLeft + 'px', top: menuTop + 'px' }"
          @mousedown.stop
        >
          <button class="history-menu-item" type="button" @click="handleRename">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M12 20h9"></path>
              <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"></path>
            </svg>
            重命名
          </button>
          <button class="history-menu-item" type="button" @click="handlePin">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M14 4l6 6"></path>
              <path d="M8 10l6 6"></path>
              <path d="M13 5l-4 4"></path>
              <path d="M3 21l6-6"></path>
              <path d="M16 8l-4 4"></path>
              <path d="M2 2l20 20"></path>
            </svg>
            置顶
          </button>
          <button class="history-menu-item" type="button" @click="handleShare">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M4 12v7a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-7"></path>
              <path d="M16 6l-4-4-4 4"></path>
              <path d="M12 2v14"></path>
            </svg>
            分享
          </button>
          <button class="history-menu-item danger" type="button" @click="handleDelete">
            <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M3 6h18"></path>
              <path d="M8 6V4h8v2"></path>
              <path d="M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6"></path>
              <path d="M10 11v6"></path>
              <path d="M14 11v6"></path>
            </svg>
            删除
          </button>
        </div>
      </div>
    </teleport>
  </div>
</template>
<script setup>
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'

const props = defineProps({ 
  history: { 
    type: Array, 
    default: () => [] 
  },
  activeConversationId: {
    type: String,
    default: '',
  },
})
const emit = defineEmits(['select', 'rename', 'pin', 'share', 'delete'])

const menuOpen = ref(false)
const menuSession = ref(null)
const menuLeft = ref(0)
const menuTop = ref(0)
const menuEl = ref(null)
let menuAnchorRect = null

function closeMenu() {
  menuOpen.value = false
  menuSession.value = null
  menuAnchorRect = null
}

function repositionMenu() {
  if (!menuOpen.value || !menuAnchorRect) return
  const margin = 10
  const rect = menuAnchorRect
  const el = menuEl.value
  const menuRect = el ? el.getBoundingClientRect() : { width: 190, height: 188 }

  let left = rect.right - menuRect.width
  left = Math.max(margin, Math.min(left, window.innerWidth - margin - menuRect.width))

  let top = rect.bottom + 8
  if (top + menuRect.height > window.innerHeight - margin) {
    top = rect.top - 8 - menuRect.height
  }
  top = Math.max(margin, Math.min(top, window.innerHeight - margin - menuRect.height))

  menuLeft.value = Math.round(left)
  menuTop.value = Math.round(top)
}

function installMenuAutoClose() {
  window.addEventListener('resize', closeMenu, { passive: true })
  window.addEventListener('scroll', closeMenu, { passive: true, capture: true })
  window.addEventListener('keydown', handleMenuKeydown)
}

function uninstallMenuAutoClose() {
  window.removeEventListener('resize', closeMenu)
  window.removeEventListener('scroll', closeMenu, true)
  window.removeEventListener('keydown', handleMenuKeydown)
}

function handleMenuKeydown(e) {
  if (e.key === 'Escape') {
    closeMenu()
  }
}

watch(menuOpen, (open) => {
  if (open) {
    installMenuAutoClose()
    nextTick(() => {
      repositionMenu()
    })
  } else {
    uninstallMenuAutoClose()
  }
})

onBeforeUnmount(() => {
  uninstallMenuAutoClose()
})

function handleSelect(session) {
  closeMenu()
  emit('select', session)
}

function toggleMenu(e, session) {
  const convId = session?.conversationId || ''
  if (!convId) return

  if (menuOpen.value && menuSession.value?.conversationId === convId) {
    closeMenu()
    return
  }

  const anchor = e?.currentTarget
  menuSession.value = session
  menuOpen.value = true
  menuAnchorRect = anchor?.getBoundingClientRect ? anchor.getBoundingClientRect() : null
  if (!menuAnchorRect) {
    closeMenu()
    return
  }
  repositionMenu()
}

function handleRename() {
  const session = menuSession.value
  if (!session?.conversationId) {
    closeMenu()
    return
  }

  const current = String(session.title || session.summaryText || '').trim()
  const nextTitle = window.prompt('重命名对话', current || '新对话')
  const trimmed = String(nextTitle || '').trim()
  if (!trimmed) {
    closeMenu()
    return
  }
  emit('rename', { session, title: trimmed })
  closeMenu()
}

function handlePin() {
  const session = menuSession.value
  if (session) {
    emit('pin', session)
  }
  closeMenu()
}

async function handleShare() {
  const session = menuSession.value
  const convId = String(session?.conversationId || '').trim()
  if (!convId) {
    closeMenu()
    return
  }
  try {
    await navigator.clipboard.writeText(convId)
  } catch (e) {
    void e
  }
  emit('share', session)
  closeMenu()
}

function handleDelete() {
  const session = menuSession.value
  if (session) {
    emit('delete', session)
  }
  closeMenu()
}

function formatSubText(item) {
  const summary = String(item?.summaryText || '').trim()
  const summaryText = summary
    ? (summary.length > 20 ? `${summary.slice(0, 20)}...` : summary)
    : ''

  const timestamp = item?.createdAt ? new Date(item.createdAt) : null
  let timeText = ''
  if (timestamp && Number.isFinite(timestamp.getTime())) {
    const mm = String(timestamp.getMonth() + 1).padStart(2, '0')
    const dd = String(timestamp.getDate()).padStart(2, '0')
    const hh = String(timestamp.getHours()).padStart(2, '0')
    const min = String(timestamp.getMinutes()).padStart(2, '0')
    timeText = `${mm}-${dd} ${hh}:${min}`
  }

  if (summaryText && timeText) {
    return `${summaryText} · ${timeText}`
  }

  if (summaryText) {
    return summaryText
  }

  if (timeText) {
    return timeText
  }

  return '点击继续会话'
}
</script>
<style scoped>
.history-list-wrapper {
  display: flex;
  flex-direction: column;
  flex: 1;
  width: 100%;
  min-height: 160px;
}

.history-empty {
  min-height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.history-item-icon {
  margin-right: 8px;
  opacity: 0.62;
  flex-shrink: 0;
}

.history-item {
  display: flex;
  align-items: flex-start;
  position: relative;
  padding-right: 2.2rem;
}

.history-item-more {
  position: absolute;
  top: 50%;
  right: 0.5rem;
  transform: translateY(-50%);
  width: 30px;
  height: 30px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--text-muted);
  cursor: pointer;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.12s ease, background-color 0.12s ease, transform 0.12s ease;
}

.history-item:hover .history-item-more,
.history-item.active .history-item-more,
.history-item.menu-open .history-item-more {
  opacity: 1;
  pointer-events: auto;
}

.history-item-more:hover {
  background: color-mix(in srgb, var(--surface-soft) 70%, transparent);
  border-color: color-mix(in srgb, var(--panel-border) 70%, transparent);
}

.history-menu-overlay {
  position: fixed;
  inset: 0;
  z-index: 100000;
  background: transparent;
}

.history-menu {
  position: fixed;
  min-width: 176px;
  padding: 6px;
  border-radius: 14px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 76%, transparent);
  background: color-mix(in srgb, var(--overlay-strong) 96%, transparent);
  box-shadow: var(--shadow-lg);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}

.history-menu-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0.62rem 0.7rem;
  border-radius: 12px;
  border: none;
  background: transparent;
  color: var(--text-main);
  cursor: pointer;
  font-weight: 600;
  letter-spacing: 0.01em;
}

.history-menu-item svg {
  flex-shrink: 0;
}

.history-menu-item:hover {
  background: var(--hover-bg);
}

.history-menu-item.danger {
  color: #ef4444;
}

.history-menu-item.danger:hover {
  background: color-mix(in srgb, #ef4444 12%, transparent);
}

.history-item-body {
  min-width: 0;
  display: grid;
  gap: 2px;
}

.history-item-text {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-item-sub {
  display: block;
  font-size: 11px;
  color: var(--text-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.history-item.active {
  border-color: color-mix(in srgb, var(--primary) 52%, var(--panel-border));
  background: color-mix(in srgb, var(--primary) 14%, transparent);
}

.history-item.active .history-item-icon,
.history-item.active .history-item-text {
  color: var(--text-main);
  opacity: 1;
}
</style>

