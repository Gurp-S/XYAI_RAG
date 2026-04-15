<template>
  <main class="main-content upload-shell">
    <HeaderBar />
    <section class="upload-workspace">
      <header class="upload-hero">
        <p class="upload-kicker">KNOWLEDGE INGEST PIPELINE</p>
        <h2>上传文档并注入知识库</h2>
        <p>
          支持拖拽与批量上传，系统会自动执行解析、增强、分块和向量入库流程。
        </p>
      </header>

      <div class="upload-grid">
        <article class="upload-card">
          <h3>1. 选择文档</h3>
          <div
            class="upload-dropzone"
            :class="{
              'is-dragover': isDragOver,
              'is-disabled': isUploading,
            }"
            role="button"
            tabindex="0"
            @click="triggerFilePicker"
            @keydown.enter.prevent="triggerFilePicker"
            @keydown.space.prevent="triggerFilePicker"
            @dragenter.prevent="handleDragEnter"
            @dragover.prevent="handleDragOver"
            @dragleave.prevent="handleDragLeave"
            @drop.prevent="handleDrop"
          >
            <input
              ref="fileInputRef"
              type="file"
              class="upload-file-input"
              accept=".pdf,.doc,.docx,.txt"
              multiple
              @change="handleFileSelect"
            >
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
              <polyline points="17 8 12 3 7 8"></polyline>
              <line x1="12" y1="3" x2="12" y2="15"></line>
            </svg>
            <strong>{{ isDragOver ? '释放文件开始加入上传队列' : '点击或拖拽文件到这里' }}</strong>
            <span>支持 PDF / DOC / DOCX / TXT，单文件建议不超过 50MB</span>
          </div>
        </article>

        <article class="upload-card">
          <h3>2. 上传配置</h3>
          <label class="upload-field" for="collectionName">
            <span>目标集合</span>
            <input
              id="collectionName"
              v-model="collectionName"
              :disabled="isUploading"
              placeholder="例如：default_collection"
            >
          </label>

          <label class="upload-field" for="kbId">
            <span>知识库 ID（可选）</span>
            <input
              id="kbId"
              v-model="kbId"
              :disabled="isUploading"
              placeholder="例如：project-onboarding"
            >
          </label>

          <div class="upload-actions">
            <button class="upload-btn primary" type="button" :disabled="!canUpload" @click="startUpload">
              {{ isUploading ? '上传中...' : '开始上传并入库' }}
            </button>
            <button class="upload-btn" type="button" :disabled="isUploading || selectedFiles.length === 0" @click="clearSelectedFiles">
              清空文件列表
            </button>
          </div>
        </article>
      </div>

      <article class="upload-card upload-state-card">
        <div class="upload-state-head">
          <h3>3. 上传状态</h3>
          <span class="upload-state-pill" :class="`is-${uploadStage}`">{{ uploadStageLabel }}</span>
        </div>

        <div v-if="selectedFiles.length === 0 && uploadStage === 'empty'" class="upload-empty-state">
          <p>当前还没有待上传文件。</p>
          <span>请选择文件后再开始上传，系统会自动执行任务进度跟踪。</span>
        </div>

        <ul v-else class="upload-file-list">
          <li v-for="(file, index) in selectedFiles" :key="`${file.name}-${file.size}-${file.lastModified}`" class="upload-file-item">
            <div class="upload-file-meta">
              <strong>{{ file.name }}</strong>
              <span>{{ formatFileSize(file.size) }}</span>
            </div>
            <button
              class="remove-file-btn"
              type="button"
              :disabled="isUploading"
              :aria-label="`移除 ${file.name}`"
              @click="removeFile(index)"
            >
              ×
            </button>
          </li>
        </ul>

        <div v-if="showProgress" class="upload-progress-wrap">
          <div class="upload-progress-track" aria-hidden="true">
            <div class="upload-progress-fill" :style="{ width: `${uploadProgress}%` }"></div>
          </div>
          <div class="upload-progress-meta">
            <span>任务进度</span>
            <strong>{{ uploadProgress }}%</strong>
          </div>
        </div>

        <p class="upload-status-text" :class="{ error: uploadStage === 'error' }">{{ uploadStatusText || defaultStatusText }}</p>
      </article>

      <article class="upload-card upload-accessibility-card">
        <h3>WCAG AA 验证点</h3>
        <ul>
          <li>交互控件与背景对比度保持不低于 4.5:1，关键状态颜色不单靠色相表达。</li>
          <li>上传区域、按钮和移除按钮均支持键盘访问，并具备可见焦点样式。</li>
          <li>进度与错误状态使用文本同步告知，避免仅用动画或图标传达信息。</li>
          <li>表单字段有明确标签，状态提示语义清晰且可被辅助技术读取。</li>
        </ul>
      </article>
    </section>
  </main>
</template>

<script setup>
import { computed, onUnmounted, ref } from 'vue'
import HeaderBar from './HeaderBar.vue'
import { ensureAccessToken } from '../services/auth'

const fileInputRef = ref(null)
const selectedFiles = ref([])
const collectionName = ref('default_collection')
const kbId = ref('')
const isDragOver = ref(false)
const isUploading = ref(false)
const uploadProgress = ref(0)
const uploadStatusText = ref('')
const uploadStage = ref('empty')
const MAX_FILE_SIZE = 50 * 1024 * 1024

let pollTimer = null
let activeTaskId = ''

const canUpload = computed(
  () =>
    !isUploading.value &&
    selectedFiles.value.length > 0 &&
    !!collectionName.value.trim(),
)

const showProgress = computed(
  () =>
    isUploading.value ||
    uploadProgress.value > 0 ||
    uploadStage.value === 'success' ||
    uploadStage.value === 'error',
)

const uploadStageLabel = computed(() => {
  if (uploadStage.value === 'ready') return '待上传'
  if (uploadStage.value === 'uploading') return '处理中'
  if (uploadStage.value === 'success') return '完成'
  if (uploadStage.value === 'error') return '失败'
  return '空状态'
})

const defaultStatusText = computed(() => {
  if (uploadStage.value === 'empty') return '等待选择文件'
  if (uploadStage.value === 'ready') return '文件已就绪，点击开始上传'
  if (uploadStage.value === 'uploading') return '正在上传与处理文件...'
  if (uploadStage.value === 'success') return '文件上传并入库完成'
  return '任务执行失败，请检查后重试'
})

function formatFileSize(size) {
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / (1024 * 1024)).toFixed(1)} MB`
}

function isAllowedFile(file) {
  const allowed = ['.pdf', '.doc', '.docx', '.txt']
  const lowerName = file.name.toLowerCase()
  return allowed.some((ext) => lowerName.endsWith(ext))
}

function upsertFiles(fileList) {
  const incoming = Array.from(fileList || [])
  if (incoming.length === 0) return

  const invalidType = incoming.filter((file) => !isAllowedFile(file))
  const invalidSize = incoming.filter((file) => file.size > MAX_FILE_SIZE)

  if (invalidType.length > 0) {
    uploadStage.value = 'error'
    uploadStatusText.value = '仅支持 PDF、DOC、DOCX、TXT 文件'
  }

  if (invalidSize.length > 0) {
    uploadStage.value = 'error'
    uploadStatusText.value = '存在超过 50MB 的文件，请压缩后重试'
  }

  const validFiles = incoming.filter(
    (file) => isAllowedFile(file) && file.size <= MAX_FILE_SIZE,
  )
  if (validFiles.length === 0) return

  const currentKeys = new Set(
    selectedFiles.value.map((file) => `${file.name}-${file.size}-${file.lastModified}`),
  )

  validFiles.forEach((file) => {
    const key = `${file.name}-${file.size}-${file.lastModified}`
    if (!currentKeys.has(key)) {
      selectedFiles.value.push(file)
      currentKeys.add(key)
    }
  })

  if (selectedFiles.value.length > 0 && uploadStage.value !== 'uploading') {
    uploadStage.value = 'ready'
    if (!uploadStatusText.value || uploadStage.value !== 'error') {
      uploadStatusText.value = ''
    }
  }
}

function triggerFilePicker() {
  if (isUploading.value || !fileInputRef.value) return
  fileInputRef.value.value = ''
  fileInputRef.value.click()
}

function handleFileSelect(event) {
  upsertFiles(event.target.files)
  event.target.value = ''
}

function handleDragEnter() {
  if (isUploading.value) return
  isDragOver.value = true
}

function handleDragOver() {
  if (isUploading.value) return
  isDragOver.value = true
}

function handleDragLeave() {
  isDragOver.value = false
}

function handleDrop(event) {
  isDragOver.value = false
  if (isUploading.value) return
  upsertFiles(event.dataTransfer.files)
}

function removeFile(index) {
  if (isUploading.value) return
  selectedFiles.value.splice(index, 1)
  if (selectedFiles.value.length === 0) {
    uploadStage.value = 'empty'
    uploadProgress.value = 0
    uploadStatusText.value = ''
  }
}

function clearSelectedFiles() {
  if (isUploading.value) return
  selectedFiles.value = []
  uploadStage.value = 'empty'
  uploadProgress.value = 0
  uploadStatusText.value = ''
}

function clearPollingTimer() {
  if (pollTimer) {
    clearTimeout(pollTimer)
    pollTimer = null
  }
}

function renderTaskSnapshot(snapshot) {
  const status = String(snapshot.status || '').toUpperCase()
  const nodeType = String(snapshot.currentNodeType || '').toLowerCase()
  const displayText = snapshot.displayText || ''
  const progress =
    typeof snapshot.progress === 'number' ? Math.max(0, Math.min(100, Math.round(snapshot.progress))) : null

  const nodeMap = {
    fetcher: '获取源文件',
    parser: '解析文档',
    enricher: '语义增强',
    chunker: '内容分块',
    indexer: '向量入库',
    start: '任务启动中',
  }

  if (status === 'WAITING') {
    uploadStatusText.value = displayText || snapshot.message || '任务排队中...'
    uploadProgress.value = progress ?? 5
    return
  }

  if (status === 'ERROR') {
    uploadStage.value = 'error'
    uploadStatusText.value = displayText || snapshot.message || snapshot.errorMessage || '处理失败'
    uploadProgress.value = progress ?? Math.max(uploadProgress.value, 88)
    return
  }

  uploadStatusText.value =
    displayText ||
    `正在执行：${nodeMap[nodeType] || snapshot.currentNodeType || '处理任务'}...`

  if (progress !== null) {
    uploadProgress.value = progress
  }
}

async function pollTaskStatus(taskId) {
    try {
    const response = await fetch(`/upload/Task?taskId=${encodeURIComponent(taskId)}`, {
      method: 'POST'
    })
    const payload = await response.json()

    if (payload.code !== 200) {
      uploadStage.value = 'error'
      uploadStatusText.value = payload.msg || '任务状态查询失败'
      isUploading.value = false
      return
    }

    const snapshot = payload.data || {}
    renderTaskSnapshot(snapshot)

    const status = String(snapshot.status || '').toUpperCase()
    if (status === 'SUCCESS') {
      uploadStage.value = 'success'
      uploadStatusText.value = snapshot.displayText || '上传完成，知识库已更新'
      uploadProgress.value =
        typeof snapshot.progress === 'number' ? Math.min(100, Math.round(snapshot.progress)) : 100
      isUploading.value = false
      return
    }

    if (status === 'ERROR') {
      isUploading.value = false
      return
    }

    pollTimer = setTimeout(() => pollTaskStatus(taskId), 420)
  } catch (error) {
    uploadStage.value = 'error'
    uploadStatusText.value = '任务状态查询失败，请稍后重试'
    isUploading.value = false
  }
}

async function startUpload() {
  if (!canUpload.value) {
    if (!collectionName.value.trim()) {
      uploadStage.value = 'error'
      uploadStatusText.value = '请先填写目标集合'
    }
    return
  }

  clearPollingTimer()
  isUploading.value = true
  uploadStage.value = 'uploading'
  uploadProgress.value = 0
  uploadStatusText.value = '正在校验登录状态...'

  const accessToken = await ensureAccessToken()
  if (!accessToken) {
    uploadStage.value = 'error'
    uploadStatusText.value = '登录状态已失效，请重新登录后再上传'
    isUploading.value = false
    return
  }

  const formData = new FormData()
  selectedFiles.value.forEach((file) => formData.append('file', file))
  formData.append('collectionName', collectionName.value.trim())
  if (kbId.value.trim()) formData.append('kbId', kbId.value.trim())

  uploadStatusText.value = '正在上传文件...'

  const xhr = new XMLHttpRequest()
  xhr.open('POST', '/upload/up', true)
  xhr.withCredentials = true
  xhr.setRequestHeader('Authorization', `Bearer ${accessToken}`)

  xhr.upload.onprogress = (event) => {
    if (!event.lengthComputable) return
    const percent = Math.round((event.loaded / event.total) * 22)
    uploadProgress.value = Math.max(uploadProgress.value, percent)
  }

  xhr.onload = () => {
    if (xhr.status < 200 || xhr.status >= 300) {
      uploadStage.value = 'error'
      uploadStatusText.value = `上传失败，服务器返回 ${xhr.status}`
      isUploading.value = false
      return
    }

    try {
      const payload = JSON.parse(xhr.responseText || '{}')
      const taskId = typeof payload.data === 'string' ? payload.data : payload?.data?.taskId
      if (!taskId) {
        uploadStage.value = 'error'
        uploadStatusText.value = '上传成功，但未获取到任务 ID'
        isUploading.value = false
        return
      }

      activeTaskId = taskId
      uploadStatusText.value = '上传完成，正在执行知识入库任务...'
      uploadProgress.value = Math.max(uploadProgress.value, 24)
      pollTaskStatus(activeTaskId)
    } catch (error) {
      uploadStage.value = 'error'
      uploadStatusText.value = '上传响应解析失败'
      isUploading.value = false
    }
  }

  xhr.onerror = () => {
    uploadStage.value = 'error'
    uploadStatusText.value = '网络异常，上传失败'
    isUploading.value = false
  }

  xhr.send(formData)
}

onUnmounted(() => {
  clearPollingTimer()
})
</script>

<style scoped>
.upload-shell {
  position: relative;
}

.upload-shell::before {
  content: "";
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(circle at 86% 4%, color-mix(in srgb, var(--primary) 18%, transparent), transparent 34%),
    radial-gradient(circle at 10% 96%, color-mix(in srgb, var(--secondary) 14%, transparent), transparent 36%);
  opacity: 0.74;
}

.upload-workspace {
  position: relative;
  z-index: 1;
  padding: 14px;
  display: grid;
  gap: 12px;
  overflow-y: auto;
}

.upload-hero {
  border-radius: 18px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 84%, transparent);
  background: color-mix(in srgb, var(--surface-solid) 90%, transparent);
  box-shadow: var(--shadow-md);
  padding: 14px;
}

.upload-kicker {
  margin: 0;
  color: var(--text-muted);
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  font-weight: 700;
}

.upload-hero h2 {
  margin: 8px 0 0;
  color: var(--text-main);
  font-size: clamp(1.2rem, 3vw, 1.64rem);
}

.upload-hero p {
  margin: 8px 0 0;
  color: var(--text-secondary);
  font-size: 0.9rem;
  line-height: 1.7;
}

.upload-grid {
  display: grid;
  gap: 12px;
  grid-template-columns: 1fr;
}

.upload-card {
  border-radius: 16px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 86%, transparent);
  background: color-mix(in srgb, var(--surface-soft) 94%, transparent);
  box-shadow: 0 10px 22px rgba(10, 18, 34, 0.24);
  padding: 12px;
}

.upload-card h3 {
  margin: 0;
  color: var(--text-main);
  font-size: 0.98rem;
}

.upload-dropzone {
  margin-top: 10px;
  position: relative;
  border-radius: 14px;
  border: 1px dashed color-mix(in srgb, var(--panel-border) 88%, transparent);
  background: color-mix(in srgb, var(--glass-soft) 90%, transparent);
  min-height: 160px;
  padding: 12px;
  display: grid;
  place-items: center;
  text-align: center;
  gap: 8px;
  cursor: pointer;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.upload-dropzone svg {
  width: 34px;
  height: 34px;
  color: var(--primary);
}

.upload-dropzone strong {
  color: var(--text-main);
  font-size: 0.95rem;
}

.upload-dropzone span {
  color: var(--text-muted);
  font-size: 0.82rem;
  line-height: 1.6;
}

.upload-dropzone:hover {
  border-color: color-mix(in srgb, var(--primary) 70%, #fff);
  transform: translateY(-1px);
}

.upload-dropzone:focus-visible {
  outline: 3px solid color-mix(in srgb, var(--primary) 34%, #fff);
  outline-offset: 2px;
}

.upload-dropzone.is-dragover {
  border-color: color-mix(in srgb, var(--primary) 78%, #fff);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--primary) 25%, transparent);
}

.upload-dropzone.is-disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.upload-file-input {
  position: absolute;
  inset: 0;
  opacity: 0;
  pointer-events: none;
}

.upload-field {
  margin-top: 10px;
  display: grid;
  gap: 6px;
}

.upload-field span {
  color: var(--text-secondary);
  font-size: 0.82rem;
  font-weight: 700;
}

.upload-field input {
  border: 1px solid color-mix(in srgb, var(--panel-border) 88%, transparent);
  background: color-mix(in srgb, var(--surface-solid) 88%, transparent);
  color: var(--text-main);
  border-radius: 10px;
  padding: 10px 11px;
  font-size: 0.9rem;
  outline: none;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.upload-field input:focus-visible {
  border-color: color-mix(in srgb, var(--primary) 68%, #fff);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--primary) 24%, transparent);
}

.upload-actions {
  margin-top: 12px;
  display: grid;
  gap: 8px;
}

.upload-btn {
  border: 1px solid color-mix(in srgb, var(--panel-border) 84%, transparent);
  background: color-mix(in srgb, var(--surface-solid) 86%, transparent);
  color: var(--text-main);
  border-radius: 10px;
  padding: 10px 12px;
  font-size: 0.88rem;
  font-weight: 700;
  cursor: pointer;
  transition: transform 0.2s ease, filter 0.2s ease;
}

.upload-btn:hover:not(:disabled) {
  transform: translateY(-1px);
}

.upload-btn.primary {
  border: none;
  color: #fff;
  background: linear-gradient(135deg, var(--primary), var(--secondary));
}

.upload-btn:focus-visible,
.remove-file-btn:focus-visible {
  outline: 3px solid color-mix(in srgb, var(--primary) 34%, #fff);
  outline-offset: 2px;
}

.upload-btn:disabled {
  cursor: not-allowed;
  opacity: 0.68;
}

.upload-state-card {
  display: grid;
  gap: 10px;
}

.upload-state-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}

.upload-state-pill {
  border-radius: 999px;
  padding: 4px 10px;
  font-size: 0.76rem;
  font-weight: 700;
  border: 1px solid transparent;
}

.upload-state-pill.is-empty,
.upload-state-pill.is-ready {
  color: var(--text-secondary);
  border-color: color-mix(in srgb, var(--panel-border) 76%, transparent);
  background: color-mix(in srgb, var(--surface-solid) 90%, transparent);
}

.upload-state-pill.is-uploading {
  color: #fff;
  border-color: color-mix(in srgb, var(--primary) 60%, transparent);
  background: color-mix(in srgb, var(--primary) 50%, transparent);
}

.upload-state-pill.is-success {
  color: #dcfce7;
  border-color: rgba(16, 185, 129, 0.5);
  background: rgba(6, 95, 70, 0.45);
}

.upload-state-pill.is-error {
  color: #fee2e2;
  border-color: rgba(248, 113, 113, 0.45);
  background: rgba(127, 29, 29, 0.45);
}

.upload-empty-state {
  border-radius: 12px;
  border: 1px dashed color-mix(in srgb, var(--panel-border) 74%, transparent);
  background: color-mix(in srgb, var(--glass-soft) 86%, transparent);
  padding: 12px;
}

.upload-empty-state p {
  margin: 0;
  color: var(--text-main);
  font-weight: 700;
}

.upload-empty-state span {
  display: block;
  margin-top: 6px;
  color: var(--text-muted);
  font-size: 0.82rem;
}

.upload-file-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 8px;
}

.upload-file-item {
  border-radius: 10px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 84%, transparent);
  background: color-mix(in srgb, var(--surface-solid) 90%, transparent);
  padding: 9px 10px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}

.upload-file-meta {
  min-width: 0;
  display: grid;
  gap: 4px;
}

.upload-file-meta strong {
  color: var(--text-main);
  font-size: 0.86rem;
  line-height: 1.35;
  word-break: break-all;
}

.upload-file-meta span {
  color: var(--text-muted);
  font-size: 0.78rem;
}

.remove-file-btn {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  border: 1px solid color-mix(in srgb, var(--panel-border) 78%, transparent);
  background: transparent;
  color: var(--text-muted);
  font-size: 16px;
  line-height: 1;
  cursor: pointer;
}

.remove-file-btn:hover:not(:disabled) {
  color: #fecaca;
  border-color: rgba(248, 113, 113, 0.5);
  background: rgba(127, 29, 29, 0.38);
}

.remove-file-btn:disabled {
  opacity: 0.54;
  cursor: not-allowed;
}

.upload-progress-wrap {
  display: grid;
  gap: 6px;
}

.upload-progress-track {
  height: 9px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--surface-solid) 70%, #334155);
  overflow: hidden;
}

.upload-progress-fill {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(135deg, var(--primary), var(--secondary));
  transition: width 0.2s ease;
}

.upload-progress-meta {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--text-muted);
  font-size: 0.78rem;
}

.upload-progress-meta strong {
  color: var(--text-main);
}

.upload-status-text {
  margin: 0;
  color: var(--text-secondary);
  font-size: 0.86rem;
  line-height: 1.65;
}

.upload-status-text.error {
  color: #fecaca;
}

.upload-accessibility-card ul {
  margin: 8px 0 0;
  padding-left: 18px;
  color: var(--text-secondary);
  display: grid;
  gap: 7px;
  line-height: 1.65;
  font-size: 0.84rem;
}

@media (min-width: 980px) {
  .upload-workspace {
    padding: 16px;
    gap: 14px;
  }

  .upload-grid {
    grid-template-columns: minmax(0, 1.1fr) minmax(0, 0.9fr);
  }

  .upload-actions {
    grid-template-columns: 1fr 1fr;
  }
}
</style>

