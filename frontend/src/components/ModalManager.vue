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
                <div class="db-table-wrapper" style="margin: 1.5rem; border: none;">
                    <table class="db-table">
                        <thead>
                            <tr>
                                <th>文档名称</th>
                                <th>切片数量 (Chunks)</th>
                                <th>入库时间</th>
                                <th>状态</th>
                            </tr>
                        </thead>
                        <tbody id="dbTableBody">
                            <tr>
                                <td>
                                    <span class="doc-icon">
                                        <svg viewBox="0 0 24 24" fill="currentColor">
                                            <path
                                                d="M12 0L3 7v10l9 7 9-7V7l-9-7zm0 2.4l7 5.4-7 5.4-7-5.4 7-5.4zm0 21l-7.5-5.8v-7.2l7.5 5.8 7.5-5.8v7.2L12 23.4z" />
                                        </svg>
                                        2026年企业战略规划.pdf
                                    </span>
                                </td>
                                <td>342</td>
                                <td>2026-03-25 14:30</td>
                                <td><span class="badge badge-success"> 已就绪</span></td>
                            </tr>
                            <tr>
                                <td>
                                    <span class="doc-icon">
                                        <svg viewBox="0 0 24 24" fill="currentColor">
                                            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6z" />
                                        </svg>
                                        员工报销管理制度.docx
                                    </span>
                                </td>
                                <td>89</td>
                                <td>2026-03-24 09:15</td>
                                <td><span class="badge badge-success"> 已就绪</span></td>
                            </tr>
                            <tr>
                                <td>
                                    <span class="doc-icon">
                                        <svg viewBox="0 0 24 24" fill="currentColor">
                                            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8l-6-6z" />
                                        </svg>
                                        Q1财务报表总结.txt
                                    </span>
                                </td>
                                <td>45</td>
                                <td>2026-03-26 10:00</td>
                                <td><span class="badge badge-process"> 向量化中</span></td>
                            </tr>
                        </tbody>
                    </table>
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
import { useUiStore } from '../store/index'

const ui = useUiStore()

const isUploading = ref(false)
const uploadProgress = ref(0)
const uploadStatusText = ref('')
const uploadFileName = ref('')

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

    // Using "file" field name and "multiple" as requested by backend (@RequestParam("file") List<MultipartFile> files)
    const formData = new FormData()
    fileArray.forEach(file => {
        formData.append('file', file)
    })

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