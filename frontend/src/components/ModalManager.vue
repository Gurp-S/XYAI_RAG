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
                        <label style="display: block; font-size: 12px; color: #94a3b8; margin-bottom: 4px;">集合名称 (Collection)</label>
                        <input type="text" v-model="collectionName" placeholder="例如: enterprise_docs" 
                               style="width: 100%; padding: 8px 12px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.1); background: rgba(0,0,0,0.2); color: white; outline: none; border-color: #6366f1;">
                    </div>
                    <div style="flex: 1;">
                        <label style="display: block; font-size: 12px; color: #94a3b8; margin-bottom: 4px;">知识库 ID (可选)</label>
                        <input type="text" v-model="kbId" placeholder="例如: kb_001" 
                               style="width: 100%; padding: 8px 12px; border-radius: 8px; border: 1px solid rgba(255,255,255,0.1); background: rgba(0,0,0,0.2); color: white; outline: none;">
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

                <div class="upload-progress-container" id="uploadProgressContainer" v-show="isUploading">
                    <div class="upload-doc-info">
                        <span id="uploadFileName">{{ uploadFileName }}</span>
                        <span id="uploadPercent">{{ uploadProgress }}%</span>
                    </div>
                    <div class="progress-bar">
                        <div class="progress-fill" id="uploadProgressBar" :style="{ width: uploadProgress + '%' }"></div>
                    </div>
                    <div class="upload-status" id="uploadStatusText">
                        <svg v-if="uploadProgress < 100" class="spin" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                            stroke-width="2">
                            <path d="M21 12a9 9 0 1 1-6.219-8.56"></path>
                        </svg>
                        {{ uploadStatusText }}
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
import { ref } from 'vue'
import Login from './Login.vue'
import MilvusManager from './MilvusManager.vue'
import { useUiStore } from '../store/index'

const ui = useUiStore()

const isUploading = ref(false)
const uploadProgress = ref(0)
const uploadStatusText = ref('')
const uploadFileName = ref('')
const collectionName = ref('default_collection')
const kbId = ref('')

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
        alert('请输入集合名称后再上传')
        return
    }

    isUploading.value = true
    uploadProgress.value = 0
    uploadStatusText.value = '正在上传并进行向量化切片...'
    
    // Convert FileList to Array and get names
    const fileArray = Array.from(files)
    let names = fileArray.map(f => f.name).join(', ')
    if (names.length > 40) {
        names = names.substring(0, 40) + '...'
    }
    uploadFileName.value = names

    const formData = new FormData()
    // Align with Backend: @RequestParam("file") List<MultipartFile> files
    fileArray.forEach(file => {
        formData.append('file', file)
    })
    // Align with Backend: @RequestParam("collectionName") String collectionName
    formData.append('collectionName', collectionName.value)
    // Align with Backend: @RequestParam(value = "kbId", required = false) String kbId
    if (kbId.value) {
        formData.append('kbId', kbId.value)
    }

    const xhr = new XMLHttpRequest()
    // Backend API mapping: @RequestMapping("/upload") + @PostMapping("up")
    xhr.open('POST', '/upload/up', true)

    // Optional: Add headers like auth token if necessary
    // xhr.setRequestHeader('Authorization', 'Bearer ' + token)

    xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
            uploadProgress.value = Math.round((e.loaded / e.total) * 100)
        }
    }

    xhr.onload = () => {
        if (xhr.status >= 200 && xhr.status < 300) {
            try {
                const res = JSON.parse(xhr.responseText)
                uploadStatusText.value = res.code === 200 ? ('上传成功！' + (res.msg || '')) : ('完成: ' + (res.msg || 'OK'))
            } catch (err) {
                uploadStatusText.value = '上传成功并进入处理队列！'
            }
        } else {
            uploadStatusText.value = '上传失败，服务器返回异常（' + xhr.status + '）。'
        }
        finishUpload(inputTarget)
    }

    xhr.onerror = () => {
        uploadStatusText.value = '网络错误，上传失败。'
        finishUpload(inputTarget)
    }

    xhr.send(formData)
}

function finishUpload(inputTarget) {
    setTimeout(() => {
        isUploading.value = false
        if (inputTarget) inputTarget.value = ''
    }, 4500)
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