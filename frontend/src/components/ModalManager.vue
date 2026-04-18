<template>
  <div>
    <!-- Upload Modal -->
    <div id="uploadModalOverlay" class="modal-overlay" :class="{ active: ui.activeModal === 'upload' }" @click.self="ui.closeModal()">
        <div class="modal">
            <div class="modal-header">
                <h3><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                        <polyline points="17 8 12 3 7 8"></polyline>
                        <line x1="12" y1="3" x2="12" y2="15"></line>
                    </svg> 知识库训练入库</h3>
                <button id="closeUploadModal" class="modal-close" @click="ui.closeModal()">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <line x1="18" y1="6" x2="6" y2="18"></line>
                        <line x1="6" y1="6" x2="18" y2="18"></line>
                    </svg>
                </button>
            </div>
            <div class="modal-body">
                <div id="uploadZone" class="upload-zone" @dragover.prevent @drop.prevent="handleFileDrop">
                    <input id="fileInput" ref="uploadFileInput" type="file" class="file-input" accept=".pdf,.doc,.docx,.txt" multiple title="" @click="resetUploadFileInput" @change="handleFileSelect" />
                    <svg
viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"
                        stroke-linejoin="round">
                        <path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"></path>
                        <polyline points="13 2 13 9 20 9"></polyline>
                    </svg>
                    <p>点击或拖拽文件到此区域上传</p>
                    <span>支持 PDF, DOCX, TXT 格式，单文件最大 50MB</span>
                </div>

                <div v-show="ui.isUploading" id="uploadProgressContainer" class="upload-progress-container">
                    <div class="upload-doc-info">
                        <span id="uploadFileName">{{ uploadFileName }}</span>
                        <!-- 隐藏不必要的百分号进度数字 -->
                    </div>
                    <div class="progress-bar">
                        <div id="uploadProgressBar" class="progress-fill" :style="{ width: ui.uploadProgress + '%' }"></div>
                    </div>
                    <div id="uploadStatusText" class="upload-status">
                        <svg
v-if="ui.uploadProgress < 100" class="spin" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                            stroke-width="2">
                            <path d="M21 12a9 9 0 1 1-6.219-8.56"></path>
                        </svg>
                        {{ ui.uploadStatusText }}
                    </div>
                </div>
            </div>
        </div>
    </div>

    <!-- DB Modal -->
    <div id="dbModalOverlay" class="modal-overlay" :class="{ active: ui.activeModal === 'db' }" @click.self="ui.closeModal()">
        <div class="modal" style="max-width: 800px;">
            <div class="modal-header">
                <h3><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <ellipse cx="12" cy="5" rx="9" ry="3"></ellipse>
                        <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path>
                        <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path>
                    </svg> 向量数据库管理 (Milvus/ES)</h3>
                <button id="closeDbModal" class="modal-close" @click="ui.closeModal()">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <line x1="18" y1="6" x2="6" y2="18"></line>
                        <line x1="6" y1="6" x2="18" y2="18"></line>
                    </svg>
                </button>
            </div>
            <div class="modal-body" style="padding: 0;">
                <!-- 静态内容，仅用于预览或管理，不再直接承载 DB 核心逻辑 -->
                <div style="padding: 40px; text-align: center; color: #94a3b8;">
                    已跳转至数据库管理主界面
                </div>
            </div>
        </div>
    </div>

    <Login v-if="ui.showLogin" />
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import Login from './Login.vue'
import { useUiStore } from '../store/index'
import { ensureAccessToken } from '../services/auth'

const ui = useUiStore()
const route = useRoute()

const uploadFileName = ref('')
const collectionName = ref('default_collection')
const kbId = ref('')
const uploadFileInput = ref(null)

let pollTimer = null
let currentTaskId = null
let taskFinished = false
let taskPollRetryCount = 0
const MAX_TASK_POLL_RETRIES = 6

watch(() => ui.activeModal, (val) => {
    if (val === 'upload' && ui.uploadTargetCollection) {
        collectionName.value = ui.uploadTargetCollection
        // Clear it so it doesn't persist forever
        ui.uploadTargetCollection = null
    }
})

function handleFileSelect(e) {
    const files = e.target.files
    if (files.length > 0) startUpload(files, e.target)
}

function resetUploadFileInput(event) {
    if (event?.target) {
        event.target.value = ''
    }
}

function handleFileDrop(e) {
    e.preventDefault()
    const files = e.dataTransfer.files
    if (files.length > 0) startUpload(files)
}

async function startUpload(files, inputTarget = null) {
    if (!collectionName.value.trim()) {
        alert('没有指定所属集合名称，无法上传。请切换合集后再试。')
        return
    }

    ui.isUploading = true
    ui.uploadProgress = 0
    ui.uploadStatusText = '正在校验登录状态...'

    const accessToken = await ensureAccessToken()
    if (!accessToken) {
        ui.isUploading = false
        ui.uploadProgress = 0
        ui.uploadStatusText = '登录状态已失效，请重新登录后再上传。'
        return
    }

    ui.uploadStatusText = '正在上传文件...'
    
    const fileArray = Array.from(files)
    const firstFileName = fileArray[0].name
    let names = fileArray.map(f => f.name).join(', ')
    if (names.length > 40) names = names.substring(0, 40) + '...'
    uploadFileName.value = names

    const formData = new FormData()
    fileArray.forEach(file => formData.append('file', file))
    formData.append('collectionName', collectionName.value)
    if (kbId.value) formData.append('kbId', kbId.value)

    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/upload/up', true)
    xhr.withCredentials = true
    xhr.setRequestHeader('Authorization', `Bearer ${accessToken}`)

    xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
            const networkProgress = Math.round((e.loaded / e.total) * 20)
            if (ui.uploadProgress < 20) ui.uploadProgress = networkProgress
        }
    }

    xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
            try {
                const payload = JSON.parse(xhr.responseText || '{}')
                const taskId = typeof payload.data === 'string' ? payload.data : payload?.data?.taskId
                if (!taskId) {
                    ui.uploadStatusText = '解析任务ID失败'
                    finishUpload(inputTarget)
                    return
                }
                ui.uploadStatusText = '准备开始注入知识库...'
                ui.closeModal()
                startPollingTask(taskId)
            } catch (err) {
                ui.uploadStatusText = '上传成功，但解析任务ID失败'
                finishUpload(inputTarget)
            }
        } else {
            ui.uploadStatusText = '上传失败，服务器返回异常（' + xhr.status + '）。'
            finishUpload(inputTarget)
        }
    }

    xhr.onerror = () => {
        ui.uploadStatusText = '网络错误，上传失败。'
        finishUpload(inputTarget)
    }

    xhr.send(formData)
    ui.closeModal()
}

function startPollingTask(taskId) {
    currentTaskId = taskId
    taskFinished = false
    taskPollRetryCount = 0
    if (pollTimer) {
        clearTimeout(pollTimer)
        pollTimer = null
    }
    console.log('开始通过 JSON 轮询接收 ETL 任务状态...')
    pollTaskStatus(taskId)
}

function scheduleTaskPoll(taskId, delay = 450) {
    if (taskFinished || taskId !== currentTaskId) return
    if (pollTimer) {
        clearTimeout(pollTimer)
    }
    pollTimer = setTimeout(() => pollTaskStatus(taskId), delay)
}

function stopPollingWithError(message) {
    taskFinished = true
    ui.uploadStatusText = message
    finishUpload()
}

async function pollTaskStatus(taskId) {
    if (taskFinished || taskId !== currentTaskId) return

    try {
        const response = await fetch(`/upload/Task?taskId=${encodeURIComponent(taskId)}`, {
            method: 'POST'
        })
        const payload = await response.json()
        if (payload.code !== 200) {
            taskPollRetryCount += 1
            if (taskPollRetryCount > MAX_TASK_POLL_RETRIES) {
                stopPollingWithError(payload.msg || '任务查询失败，请稍后重试。')
                return
            }
            ui.uploadStatusText = `${payload.msg || '任务查询失败'}（重试 ${taskPollRetryCount}/${MAX_TASK_POLL_RETRIES}）`
            scheduleTaskPoll(taskId, Math.min(1400, 450 + taskPollRetryCount * 150))
            return
        }

        taskPollRetryCount = 0
        const snapshot = payload.data || {}
        renderTaskSnapshot(snapshot)

        if (String(snapshot.status || '').toUpperCase() === 'SUCCESS' || String(snapshot.status || '').toUpperCase() === 'ERROR') {
            taskFinished = true
            if (String(snapshot.status || '').toUpperCase() === 'ERROR') {
                ui.uploadStatusText = snapshot.displayText || snapshot.message || snapshot.errorMessage || '处理失败'
            } else {
                ui.uploadStatusText = snapshot.displayText || '处理完成'
                ui.uploadProgress = typeof snapshot.progress === 'number' ? snapshot.progress : 100
            }
            finishUpload()
            return
        }

        scheduleTaskPoll(taskId, 450)
    } catch (err) {
        if (taskFinished || taskId !== currentTaskId) return

        taskPollRetryCount += 1
        if (taskPollRetryCount > MAX_TASK_POLL_RETRIES) {
            stopPollingWithError('任务状态查询失败，请检查服务后重试。')
            return
        }

        ui.uploadStatusText = `任务状态查询失败，正在重试（${taskPollRetryCount}/${MAX_TASK_POLL_RETRIES}）...`
        scheduleTaskPoll(taskId, Math.min(1600, 700 + taskPollRetryCount * 180))
    }
}

function renderTaskSnapshot(snapshot) {
    const status = String(snapshot.status || '').toUpperCase()
    const nodeType = snapshot.currentNodeType || snapshot.message || ''
    const displayText = snapshot.displayText || ''
    const progress = typeof snapshot.progress === 'number' ? snapshot.progress : null

    if (status === 'WAITING') {
        ui.uploadStatusText = displayText || snapshot.message || '任务准备中...'
        ui.uploadProgress = progress ?? 5
        return
    }

    if (status === 'ERROR') {
        ui.uploadStatusText = displayText || snapshot.message || snapshot.errorMessage || '处理失败'
        ui.uploadProgress = progress ?? 100
        return
    }

    const nodeMap = {
        fetcher: '获取源文件',
        parser: '解析文档',
        enricher: '语义增强',
        chunker: '内容分块',
        indexer: '向量入库',
        start: '任务启动中',
    }

    const lowerType = String(nodeType).toLowerCase()
    ui.uploadStatusText = displayText || `正在执行: ${nodeMap[lowerType] || nodeType}...`
    if (progress !== null) {
        ui.uploadProgress = progress
    } else {
        const nodeOrder = ['fetcher', 'parser', 'enricher', 'chunker', 'indexer']
        const idx = nodeOrder.indexOf(lowerType)
        if (idx !== -1) {
            ui.uploadProgress = 20 + Math.round((idx / (nodeOrder.length - 1)) * 75)
        }
    }
}

async function fetchTaskSuccess() {
    // 任务成功后，如果当前在数据库管理视图，自动刷新列表
    if (route.path.startsWith('/db')) {
        // 这里只是一个提示，具体的刷新可以由父组件或对应的管理器监听
    }
}

function finishUpload() {
    if (pollTimer) {
        clearTimeout(pollTimer)
        pollTimer = null
    }
    currentTaskId = null
    taskPollRetryCount = 0
    setTimeout(() => {
        ui.isUploading = false;
        if (uploadFileInput.value) {
            uploadFileInput.value.value = '';
        }
    }, 1500);
}
</script>

<style scoped>
@keyframes spin {
  100% {
    transform: rotate(360deg);
  }
}
.spin {
  animation: spin 1s linear infinite;
}
</style>