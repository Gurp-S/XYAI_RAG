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

let pollInterval = null

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

function startPollingTask(fileName) {
    if (pollInterval) clearInterval(pollInterval)
    console.log('开始轮询任务:', fileName)
    pollInterval = setInterval(async () => {
        try {
            const res = await fetch(`/upload/Task?fileName=${encodeURIComponent(fileName)}`)
            
            const contentType = res.headers.get("content-type")
            if (!contentType || !contentType.includes("application/json")) {
                return
            }

            const data = await res.json()
            console.log('轮询原始数据:', data)
            
            if (data.code === 200 && data.data) {
                const info = data.data
                ui.uploadProgress = info.progress || 0
                
                const statusStr = (info.status || '').toUpperCase().trim()
                
                if (statusStr === 'WAITING') {
                    ui.uploadStatusText = '任务排队中...'
                } else if (statusStr.includes('FAILED')) {
                    ui.uploadStatusText = `解析失败: ${info.status}`
                    clearInterval(pollInterval)
                    finishUpload()
                } else if (statusStr === 'COMPLETED' || info.progress === 100) {
                    ui.uploadStatusText = '入库完成'
                    ui.uploadProgress = 100
                    clearInterval(pollInterval)
                    finishUpload()
                } else if (statusStr.includes('PROCESSING:')) {
                    // 更加鲁棒的字符串截取
                    const nodeType = statusStr.split(':')[1]?.trim() || ''
                    const nodeMap = {
                        'FETCHER': '获取源文件',
                        'PARSER': '解析文档',
                        'ENRICHER': '语义增强',
                        'CHUNKER': '内容分块',
                        'INDEXER': '向量入库'
                    }
                    ui.uploadStatusText = `正在${nodeMap[nodeType] || nodeType}...`
                } else {
                    ui.uploadStatusText = info.status
                }
            } else if (data.code === 404) {
                clearInterval(pollInterval)
                finishUpload()
            }
        } catch (err) {
            console.error('轮询出错', err)
        }
    }, 1000)
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

    xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
            const networkProgress = Math.round((e.loaded / e.total) * 20)
            if (ui.uploadProgress < 20) ui.uploadProgress = networkProgress
        }
    }

    xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
            ui.uploadStatusText = '上传成功，正在送入 ETL 队列解析...'
            // 强制关闭模态框
            ui.activeModal = null
            // 确保同步给 Pinia 的状态是 false，彻底关掉遮罩
            const overlay = document.getElementById('uploadModalOverlay')
            if (overlay) overlay.classList.remove('active')
            
            startPollingTask(firstFileName)
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

function finishUpload(inputTarget) {
    if (pollInterval) {
        clearInterval(pollInterval)
        pollInterval = null
    }
    setTimeout(() => {
        ui.isUploading = false
        if (inputTarget) inputTarget.value = ''
    }, 1500)
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