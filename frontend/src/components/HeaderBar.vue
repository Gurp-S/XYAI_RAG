<template>
  <header class="header">
    <div class="header-title">
        <button id="toggleSidebar" class="sidebar-toggle header-sidebar-toggle" title="收起/展开侧边栏" @click="store.toggleSidebar()">
            <svg
width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="3" y1="12" x2="21" y2="12"></line>
                <line x1="3" y1="6" x2="21" y2="6"></line>
                <line x1="3" y1="18" x2="21" y2="18"></line>
            </svg>
        </button>
        对话主控制台
        <span class="enterprise-badge">ENTERPRISE</span>
    </div>
    <div class="header-controls">
        <div class="status-badge">
            <div class="status-dot"></div> <span id="status">系统就绪</span>
        </div>
        <button
            class="theme-toggle dark-quick-toggle"
            :class="{ active: isDark }"
            type="button"
            title="切换暗夜模式"
            @click="toggleThemeLocally"
        >
            <svg v-if="!isDark" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <circle cx="12" cy="12" r="4"></circle>
                <path d="M12 2v2"></path>
                <path d="M12 20v2"></path>
                <path d="m4.93 4.93 1.41 1.41"></path>
                <path d="m17.66 17.66 1.41 1.41"></path>
                <path d="M2 12h2"></path>
                <path d="M20 12h2"></path>
                <path d="m6.34 17.66-1.41 1.41"></path>
                <path d="m19.07 4.93-1.41 1.41"></path>
            </svg>
            <svg v-else width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                <path d="M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9z"></path>
            </svg>
        </button>
        <div ref="appearanceAnchor" class="appearance-anchor">
            <button id="toggleTheme" class="theme-toggle appearance-trigger" title="外观与主题" @click.stop="toggleAppearancePanel">
                <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                    <circle cx="12" cy="12" r="10"></circle>
                    <circle cx="12" cy="12" r="4"></circle>
                    <line x1="21.17" y1="8" x2="12" y2="8"></line>
                    <line x1="3.95" y1="6.06" x2="8.54" y2="14"></line>
                    <line x1="10.88" y1="21.94" x2="15.46" y2="14"></line>
                </svg>
            </button>

            <transition name="panel-fade">
                <div v-if="showAppearancePanel" ref="appearancePanel" class="appearance-panel" @click.stop>
                    <div class="panel-head">
                        <h4>外观主题</h4>
                        <button class="mini-close" aria-label="关闭主题面板" @click="showAppearancePanel = false">×</button>
                    </div>

                    <div class="section-title">主界面配色</div>
                    <div class="theme-grid">
                        <button
                            v-for="item in themeOptions"
                            :key="item.name"
                            class="theme-chip"
                            :class="{ active: item.name === store.themeName }"
                            @click="changeTheme(item.name)"
                        >
                            <span class="chip-dot" :style="{ background: item.preview }"></span>
                            <span>{{ item.label }}</span>
                        </button>
                    </div>

                    <div class="section-title">侧栏按钮模式</div>
                    <div class="style-row">
                        <button
                            class="style-chip"
                            :class="{ active: store.sidebarMode === 'fullscreen' }"
                            @click="store.setSidebarMode('fullscreen')"
                        >
                            <span class="chip-dot" style="background: linear-gradient(135deg,#94a3b8,#475569)"></span>
                            <span>全屏</span>
                        </button>
                        <button
                            class="style-chip"
                            :class="{ active: store.sidebarMode === 'collapse' }"
                            @click="store.setSidebarMode('collapse')"
                        >
                            <span class="chip-dot" style="background: linear-gradient(135deg,#cbd5e1,#64748b)"></span>
                            <span>收起</span>
                        </button>
                    </div>

                    <div class="section-title">消息气泡</div>
                    <div class="style-row" style="justify-content: space-between; align-items: center; cursor: pointer; padding: 4px 6px;" @click="toggleBotBubble">
                        <span style="font-size: 13px; color: var(--text-main);">AI 返回消息底色</span>
                        <div class="toggle-switch" :class="{ 'is-active': store.botBubbleEnabled }">
                            <div class="toggle-knob"></div>
                        </div>
                    </div>

                    <div class="section-title blur-title">
                        <span>背景模糊</span>
                        <strong>{{ backgroundBlur }}px</strong>
                    </div>
                    <input
                        class="blur-slider"
                        type="range"
                        min="0"
                        max="24"
                        step="1"
                        :value="backgroundBlur"
                        @input="handleBackgroundBlurChange"
                        @change="flushBackgroundBlurChange"
                    >

                    <div class="panel-actions">
                        <button class="panel-btn" type="button" @click="triggerBackgroundUpload">上传背景图</button>
                        <button v-if="store.backgroundImage" class="panel-btn danger" type="button" @click="clearBackground">清除背景图</button>
                    </div>

                    <input
                        ref="backgroundUploader"
                        type="file"
                        accept="image/png,image/jpeg,image/webp,image/gif"
                        class="background-input"
                        @change="handleBackgroundUpload"
                    >

                    <div class="section-title">AI 头像</div>
                    <div class="panel-actions">
                        <button class="panel-btn" type="button" @click="triggerAiAvatarUpload">上传头像</button>
                        <button v-if="store.aiAvatar" class="panel-btn danger" type="button" @click="clearAiAvatar">清除头像</button>
                    </div>
                    <div v-if="store.aiAvatar" class="ai-avatar-preview">
                        <img :src="store.aiAvatar" alt="AI头像预览" class="ai-avatar-preview-img" />
                    </div>
                    <input
                        ref="aiAvatarUploader"
                        type="file"
                        accept="image/png,image/jpeg,image/webp,image/gif"
                        class="background-input"
                        @change="handleAiAvatarUpload"
                    >
                </div>
            </transition>
        </div>

        <button id="newConversation" class="btn-ghost" type="button" title="开启新会话" @click="handleNewChat">
            <svg
width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                <line x1="12" y1="5" x2="12" y2="19"></line>
                <line x1="5" y1="12" x2="19" y2="12"></line>
            </svg>
            新对话
        </button>
    </div>
  </header>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useUiStore } from '../store/index'

const store = useUiStore()
const isDark = computed(() => store.darkMode)
const showAppearancePanel = ref(false)
const appearanceAnchor = ref(null)
const appearancePanel = ref(null)
const backgroundUploader = ref(null)
const backgroundBlur = ref(store.backgroundBlur ?? 4)
const MAX_BACKGROUND_SIZE = 5 * 1024 * 1024
let blurCommitRafId = 0
let queuedBackgroundBlur = Number.isFinite(Number(store.backgroundBlur))
    ? Number(store.backgroundBlur)
    : 4

const themeOptions = [
    { name: 'deep-space', label: '夜蓝', preview: 'linear-gradient(135deg,#334155,#2563eb)' },
    { name: 'ink-gold', label: '纸墨', preview: 'linear-gradient(135deg,#a16207,#c2410c)' },
    { name: 'pine-night', label: '松雾', preview: 'linear-gradient(135deg,#0f766e,#16a34a)' },
    { name: 'cyber-violet', label: '暮紫', preview: 'linear-gradient(135deg,#6d28d9,#2563eb)' },
]

function handleNewChat() {
    showAppearancePanel.value = false
    store.newConversation();
    // Ensure input is focused after starting a new conversation so send remains responsive
    setTimeout(() => {
        try {
            const el = document.getElementById('message');
            if (el && typeof el.focus === 'function') el.focus();
        } catch (e) {
            // ignore
        }
    }, 0);
}

function toggleThemeLocally() {
    store.toggleDarkMode()
}

function toggleBotBubble() {
    store.setBotBubbleEnabled(!store.botBubbleEnabled)
}

function toggleAppearancePanel() {
    showAppearancePanel.value = !showAppearancePanel.value
}

function changeTheme(themeName) {
    store.setTheme(themeName)
}

function triggerBackgroundUpload() {
    if (!backgroundUploader.value) return
    backgroundUploader.value.value = ''
    backgroundUploader.value.click()
}

function fileToDataUrl(file) {
    return new Promise((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => {
            if (typeof reader.result === 'string') {
                resolve(reader.result)
            } else {
                reject(new Error('读取图片失败'))
            }
        }
        reader.onerror = () => reject(new Error('读取图片失败'))
        reader.readAsDataURL(file)
    })
}

async function compressImageForStorage(file) {
    const maxSide = 1920

    if (typeof createImageBitmap !== 'function') {
        return fileToDataUrl(file)
    }

    const bitmap = await createImageBitmap(file)
    const scale = Math.min(1, maxSide / bitmap.width, maxSide / bitmap.height)
    const targetWidth = Math.max(1, Math.round(bitmap.width * scale))
    const targetHeight = Math.max(1, Math.round(bitmap.height * scale))

    const canvas = document.createElement('canvas')
    canvas.width = targetWidth
    canvas.height = targetHeight

    const ctx = canvas.getContext('2d', { alpha: false })
    if (!ctx) {
        bitmap.close?.()
        return fileToDataUrl(file)
    }

    ctx.drawImage(bitmap, 0, 0, targetWidth, targetHeight)
    bitmap.close?.()

    return canvas.toDataURL('image/webp', 0.82)
}

async function handleBackgroundUpload(event) {
    const file = event.target.files?.[0]
    if (!file) return

    if (!file.type.startsWith('image/')) {
        alert('请选择图片文件')
        return
    }

    if (file.size > MAX_BACKGROUND_SIZE) {
        alert('图片请控制在 5MB 以内')
        event.target.value = ''
        return
    }

    try {
        const dataUrl =
            file.size > 1.2 * 1024 * 1024
                ? await compressImageForStorage(file)
                : await fileToDataUrl(file)

        const saved = store.setBackgroundImage(dataUrl)
        if (!saved) {
            alert('图片已超出浏览器本地存储上限，请换更小图片')
            return
        }
    } catch (err) {
        console.error('背景图片处理失败', err)
        alert('图片处理失败，请重试')
    } finally {
        event.target.value = ''
    }
}

function clearBackground() {
    store.clearBackgroundImage()
}

const aiAvatarUploader = ref(null)
const MAX_AVATAR_SIZE = 2 * 1024 * 1024

function triggerAiAvatarUpload() {
    if (!aiAvatarUploader.value) return
    aiAvatarUploader.value.value = ''
    aiAvatarUploader.value.click()
}

async function handleAiAvatarUpload(event) {
    const file = event.target?.files?.[0]
    if (!file) return
    if (file.size > MAX_AVATAR_SIZE) {
        alert('头像图片不能超过 2MB')
        event.target.value = ''
        return
    }
    try {
        const dataUrl = await fileToDataUrl(file)
        store.setAiAvatar(dataUrl)
    } catch (err) {
        console.error('AI头像处理失败', err)
        alert('头像处理失败，请重试')
    } finally {
        event.target.value = ''
    }
}

function clearAiAvatar() {
    store.setAiAvatar('')
}

function handleBackgroundBlurChange(event) {
    const value = Number(event.target.value)
    backgroundBlur.value = Number.isFinite(value) ? value : 0
    queuedBackgroundBlur = backgroundBlur.value

    if (blurCommitRafId) {
        return
    }

    blurCommitRafId = requestAnimationFrame(() => {
        blurCommitRafId = 0
        store.setBackgroundBlur(queuedBackgroundBlur)
    })
}

function flushBackgroundBlurChange() {
    if (blurCommitRafId) {
        cancelAnimationFrame(blurCommitRafId)
        blurCommitRafId = 0
    }
    store.setBackgroundBlur(queuedBackgroundBlur)
}

function handleGlobalClick(event) {
    if (!showAppearancePanel.value) return

    const anchor = appearanceAnchor.value
    if (anchor && !anchor.contains(event.target)) {
        showAppearancePanel.value = false
    }
}

watch(() => store.backgroundBlur, (newVal) => {
    backgroundBlur.value = Number.isFinite(Number(newVal)) ? Number(newVal) : 4
})

onMounted(() => {
    document.addEventListener('click', handleGlobalClick)
})

onUnmounted(() => {
    document.removeEventListener('click', handleGlobalClick)

    if (blurCommitRafId) {
        cancelAnimationFrame(blurCommitRafId)
        blurCommitRafId = 0
    }
})
</script>

<style scoped>
.appearance-anchor {
    position: relative;
    z-index: 30;
}

.appearance-trigger,
.dark-quick-toggle,
.mini-close,
.theme-chip,
.style-chip,
.panel-btn {
    transition:
        transform var(--feedback-fast, 140ms) var(--feedback-ease, cubic-bezier(0.2, 0.8, 0.2, 1)),
        background-color var(--feedback-fast, 140ms) var(--feedback-ease, cubic-bezier(0.2, 0.8, 0.2, 1)),
        border-color var(--feedback-fast, 140ms) var(--feedback-ease, cubic-bezier(0.2, 0.8, 0.2, 1)),
        color var(--feedback-fast, 140ms) var(--feedback-ease, cubic-bezier(0.2, 0.8, 0.2, 1)),
        box-shadow var(--feedback-medium, 170ms) var(--feedback-ease, cubic-bezier(0.2, 0.8, 0.2, 1));
}

.appearance-trigger {
    position: relative;
}

.appearance-trigger:hover,
.dark-quick-toggle:hover,
.theme-chip:hover,
.style-chip:hover,
.panel-btn:hover,
.mini-close:hover {
    transform: translateY(-1px);
}

.theme-chip:active,
.style-chip:active,
.panel-btn:active,
.mini-close:active,
.appearance-trigger:active,
.dark-quick-toggle:active {
    transform: translateY(0);
}

.dark-quick-toggle.active {
    color: #f8fafc;
    border-color: color-mix(in srgb, var(--primary) 56%, var(--panel-border));
    background: color-mix(in srgb, var(--primary) 28%, var(--surface-soft));
    box-shadow: 0 8px 18px color-mix(in srgb, var(--primary) 24%, transparent);
}

.appearance-panel {
    position: absolute;
    right: 0;
    top: calc(100% + 10px);
    width: 348px;
    max-height: none;
    overflow-y: visible;
    border-radius: 16px;
    border: 1px solid var(--panel-border);
    background: color-mix(in srgb, var(--overlay-strong) 96%, var(--bg-base));
    box-shadow:
        0 28px 60px rgba(5, 12, 24, 0.30),
        0 1px 0 rgba(255, 255, 255, 0.04) inset;
    backdrop-filter: blur(18px) saturate(120%);
    -webkit-backdrop-filter: blur(18px) saturate(120%);
    padding: 14px;
    z-index: 120;
}

.panel-head {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 8px;
}

.panel-head h4 {
    margin: 0;
    font-size: 14px;
    color: var(--text-main);
}

.mini-close {
    width: 24px;
    height: 24px;
    border: none;
    border-radius: 8px;
    background: var(--glass-soft);
    color: var(--text-muted);
    cursor: pointer;
}

.mini-close:hover {
    color: var(--text-main);
    background: var(--hover-bg);
}

.appearance-line {
    width: 100%;
    border-radius: 10px;
    border: 1px solid var(--panel-border);
    background: var(--glass-soft);
    color: var(--text-main);
    padding: 8px 10px;
    display: flex;
    justify-content: space-between;
    align-items: center;
    cursor: pointer;
}

.appearance-line strong {
    color: var(--primary);
    font-size: 12px;
}

.section-title {
    margin-top: 12px;
    margin-bottom: 8px;
    font-size: 13px;
    color: var(--text-muted);
}

.theme-grid,
.style-row {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
}

.theme-chip,
.style-chip {
    width: 100%;
    border: 1px solid var(--panel-border);
    background: transparent;
    color: var(--text-main);
    border-radius: 12px;
    padding: 10px;
    text-align: left;
    display: inline-flex;
    align-items: center;
    gap: 8px;
    cursor: pointer;
    min-height: 44px;
}

.theme-chip:hover,
.style-chip:hover {
    background: var(--hover-bg);
}

.theme-chip.active,
.style-chip.active {
    border-color: color-mix(in srgb, var(--primary) 50%, var(--panel-border));
    background: color-mix(in srgb, var(--primary) 16%, transparent);
}

.toggle-switch {
    width: 32px; height: 18px;
    border-radius: 12px;
    background: color-mix(in srgb, var(--panel-border) 60%, transparent);
    position: relative;
    transition: background-color 0.2s;
}
.toggle-switch.is-active {
    background: var(--primary);
}
.toggle-knob {
    position: absolute;
    left: 2px; top: 2px;
    width: 14px; height: 14px;
    background: #fff;
    border-radius: 50%;
    transition: transform 0.2s cubic-bezier(0.22, 1, 0.36, 1);
    box-shadow: 0 1px 3px rgba(0,0,0,0.15);
}
.toggle-switch.is-active .toggle-knob {
    transform: translateX(14px);
}

.chip-dot {
    width: 12px;
    height: 12px;
    border-radius: 999px;
    flex-shrink: 0;
}

.blur-title {
    display: flex;
    align-items: center;
    justify-content: space-between;
}

.blur-title strong {
    color: var(--text-main);
    font-size: 12px;
}

.blur-slider {
    width: 100%;
    accent-color: var(--primary);
    cursor: pointer;
}

.panel-actions {
    margin-top: 12px;
    display: flex;
    gap: 8px;
}

.panel-btn {
    flex: 1;
    border: 1px solid var(--panel-border);
    border-radius: 10px;
    padding: 9px 10px;
    background: var(--glass-soft);
    color: var(--text-main);
    font-size: 13px;
    cursor: pointer;
}

.panel-btn:hover {
    background: var(--hover-bg);
    box-shadow: 0 8px 16px color-mix(in srgb, var(--primary) 18%, transparent);
}

.panel-btn.danger {
    color: #dc2626;
    border-color: color-mix(in srgb, #dc2626 48%, var(--panel-border));
}

.background-input {
    display: none;
}

.ai-avatar-preview {
    margin-top: 8px;
    display: flex;
    justify-content: center;
}
.ai-avatar-preview-img {
    width: 56px;
    height: 56px;
    border-radius: 50%;
    object-fit: cover;
    border: 2px solid var(--panel-border);
    box-shadow: 0 4px 12px rgba(0,0,0,0.1);
}

.panel-fade-enter-active,
.panel-fade-leave-active {
    transition:
        opacity var(--motion-fast, 0.18s) var(--motion-ease, ease),
        transform var(--motion-fast, 0.18s) var(--motion-ease, ease);
}

.panel-fade-enter-from,
.panel-fade-leave-to {
    opacity: 0;
    transform: translateY(-6px) scale(0.985);
}

@media (max-width: 768px) {
    .appearance-panel {
        width: min(92vw, 348px);
        right: -4px;
        padding: 12px;
    }
}
</style>