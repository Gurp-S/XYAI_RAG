<template>
  <div>
    <!-- Upload Modal -->
    <div class="modal-overlay" id="uploadModalOverlay" :class="{ active: ui.activeModal === 'upload' }" @click.self="ui.closeModal()">
        <div class="modal">
            <div class="modal-header">
                <h3><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
                        <polyline points="17 8 12 3 7 8"></polyline>
                        <line x1="12" y1="3" x2="12" y2="15"></line>
                    </svg> 知识库训练入库</h3>
                <button class="modal-close" id="closeUploadModal" @click="ui.closeModal()">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <line x1="18" y1="6" x2="6" y2="18"></line>
                        <line x1="6" y1="6" x2="18" y2="18"></line>
                    </svg>
                </button>
            </div>
            <div class="modal-body">
                <div style="margin-bottom: 1rem; display: flex; gap: 10px;">
                    <div style="flex: 1;">
                        <label style="display: block; font-size: 13px; color: #94a3b8; margin-bottom: 8px;">知识库 ID (可选，建议留空由系统自动生成)</label>
                        <input type="text" v-model="kbId" placeholder="例如: kb_001" 
                               style="width: 100%; padding: 10px 14px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.1); background: rgba(0,0,0,0.2); color: white; outline: none; transition: border-color 0.2s;">
                    </div>
                </div>
                <div class="upload-zone" id="uploadZone" @dragover.prevent @drop.prevent="handleFileDrop">
                    <input type="file" class="file-input" id="fileInput" accept=".pdf,.doc,.docx,.txt" multiple @change="handleFileSelect" title="" />
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"
                        stroke-linejoin="round">
                        <path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"></path>
                        <polyline points="13 2 13 9 20 9"></polyline>
                    </svg>
                    <p>点击或拖拽文件到此区域上传</p>
                    <span>支持 PDF, DOCX, TXT 格式，单文件最大 50MB</span>
                </div>

                <div class="upload-progress-container" id="uploadProgressContainer" v-show="ui.isUploading">
                    <div class="upload-doc-info">
                        <span id="uploadFileName">{{ uploadFileName }}</span>
                        <!-- 隐藏不必要的百分号进度数字 -->
                    </div>
                    <div class="progress-bar">
                        <div class="progress-fill" id="uploadProgressBar" :style="{ width: ui.uploadProgress + '%' }"></div>
                    </div>
                    <div class="upload-status" id="uploadStatusText">
                        <svg v-if="ui.uploadProgress < 100" class="spin" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
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
    <div class="modal-overlay" id="dbModalOverlay" :class="{ active: ui.activeModal === 'db' }" @click.self="ui.closeModal()">
        <div class="modal" style="max-width: 800px;">
            <div class="modal-header">
                <h3><svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <ellipse cx="12" cy="5" rx="9" ry="3"></ellipse>
                        <path d="M21 12c0 1.66-4 3-9 3s-9-1.34-9-3"></path>
                        <path d="M3 5v14c0 1.66 4 3 9 3s9-1.34 9-3V5"></path>
                    </svg> 向量数据库管理 (Milvus/ES)</h3>
                <button class="modal-close" id="closeDbModal" @click="ui.closeModal()">
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
import Login from './Login.vue'
import MilvusManager from './MilvusManager.vue'
import { useUiStore } from '../store/index'

const ui = useUiStore()

const uploadFileName = ref('')
const collectionName = ref('default_collection')
const kbId = ref('')

let pollTimer = null
let currentTaskId = null
let taskFinished = false

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

function handleFileDrop(e) {
    e.preventDefault()
    const files = e.dataTransfer.files
    if (files.length > 0) startUpload(files)
}

function startUpload(files, inputTarget = null) {
    if (!collectionName.value.trim()) {
        alert('没有指定所属集合名称，无法上传。请切换合集后再试。')
        return
    }

    ui.isUploading = true
    ui.uploadProgress = 0
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

    // 只要前端一接收到文件并开始提交，就立即关闭模态框
    ui.closeModal()

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
}

function startPollingTask(taskId) {
    currentTaskId = taskId
    taskFinished = false
    if (pollTimer) {
        clearTimeout(pollTimer)
        pollTimer = null
    }
    console.log('开始通过 JSON 轮询接收 ETL 任务状态...')
    pollTaskStatus(taskId)
}

async function pollTaskStatus(taskId) {
    try {
        const response = await fetch(`/upload/Task?taskId=${encodeURIComponent(taskId)}`)
        const payload = await response.json()
        if (payload.code !== 200) {
            ui.uploadStatusText = payload.msg || '任务查询失败'
            if (!taskFinished) {
                pollTimer = setTimeout(() => pollTaskStatus(taskId), 400)
            }
            return
        }

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

        pollTimer = setTimeout(() => pollTaskStatus(taskId), 400)
    } catch (err) {
        if (!taskFinished) {
            ui.uploadStatusText = '任务状态查询失败，正在重试...'
            pollTimer = setTimeout(() => pollTaskStatus(taskId), 800)
        }
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
    if (useUiStore().currentView === 'db') {
        // 这里只是一个提示，具体的刷新可以由父组件或对应的管理器监听
    }
}

function finishUpload() {
    if (pollTimer) {
        clearTimeout(pollTimer)
        pollTimer = null
    }
    setTimeout(() => {
        ui.isUploading = false;
        // 重置文件输入框，确保下次选择相同文件也能触发 change 事件
        const fileInput = document.getElementById('fileInput');
        if (fileInput) {
            fileInput.value = '';
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