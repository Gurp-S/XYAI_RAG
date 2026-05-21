<template>
  <div class="view-flex">
    <div class="view__header">
      <h2 class="view__title">公告管理</h2>
      <p class="view__subtitle">发送和管理系统公告</p>
    </div>

    <el-row :gutter="16">
      <el-col :span="10">
        <el-card shadow="never">
          <template #header>发送公告</template>
          <el-form label-width="0">
            <el-input
              v-model="newContent"
              type="textarea"
              :rows="4"
              placeholder="输入公告内容..."
              maxlength="500"
              show-word-limit
            />
            <el-button type="primary" class="mt-3" @click="send" :loading="sending" style="width: 100%">
              发送公告
            </el-button>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="14">
        <el-card shadow="never">
          <template #header>历史公告</template>
          <div v-if="list.length === 0" style="color: var(--text-secondary); text-align: center; padding: 40px 0;">
            暂无公告
          </div>
          <div v-for="item in list" :key="item.id" class="announcement-item">
            <div class="announcement-item__header">
              <el-tag size="small" type="primary" effect="plain">#{{ item.id?.substring(0, 8) }}</el-tag>
              <span class="announcement-item__time">{{ formatTime(item.timestamp) }}</span>
              <div class="announcement-item__actions">
                <el-button size="small" text type="primary" @click="startEdit(item)">编辑</el-button>
                <el-popconfirm title="确认删除？" @confirm="handleDelete(item.id)">
                  <template #reference>
                    <el-button size="small" text type="danger">删除</el-button>
                  </template>
                </el-popconfirm>
              </div>
            </div>
            <div class="announcement-item__content">{{ item.content }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Edit Dialog -->
    <el-dialog v-model="editVisible" title="编辑公告" width="480px">
      <el-input v-model="editContent" type="textarea" :rows="4" maxlength="500" show-word-limit />
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="confirmEdit" :loading="editing">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as announcementApi from '../../api/announcement'
import dayjs from 'dayjs'

const newContent = ref('')
const list = ref([])
const sending = ref(false)

const editVisible = ref(false)
const editContent = ref('')
const editId = ref('')
const editing = ref(false)

function formatTime(ts) {
  if (!ts) return '-'
  return dayjs(ts).format('YYYY-MM-DD HH:mm')
}

async function load() {
  try { list.value = await announcementApi.getAnnouncementHistory() || [] }
  catch (e) { list.value = [] }
}

async function send() {
  if (!newContent.value.trim()) { ElMessage.warning('请输入公告内容'); return }
  sending.value = true
  try {
    await announcementApi.sendAnnouncement(newContent.value)
    ElMessage.success('公告已发送')
    newContent.value = ''
    load()
  } catch (e) { /* handled */ }
  finally { sending.value = false }
}

function startEdit(item) {
  editId.value = item.id
  editContent.value = item.content
  editVisible.value = true
}

async function confirmEdit() {
  if (!editContent.value.trim()) { ElMessage.warning('请输入内容'); return }
  editing.value = true
  try {
    await announcementApi.updateAnnouncement(editId.value, editContent.value)
    ElMessage.success('公告已更新')
    editVisible.value = false
    load()
  } catch (e) { /* handled */ }
  finally { editing.value = false }
}

async function handleDelete(id) {
  try {
    await announcementApi.deleteAnnouncement(id)
    ElMessage.success('公告已删除')
    load()
  } catch (e) { /* handled */ }
}

onMounted(load)
</script>

<style scoped>
.mt-3 { margin-top: 12px; }
.announcement-item {
  padding: 12px 0;
  border-bottom: 1px solid var(--border-light);
}
.announcement-item:last-child { border-bottom: none; }
.announcement-item__header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.announcement-item__time {
  font-size: 12px;
  color: var(--text-secondary);
  flex: 1;
}
.announcement-item__actions {
  display: flex;
  gap: 4px;
}
.announcement-item__content {
  font-size: 14px;
  color: var(--text-primary);
  line-height: 1.6;
  white-space: pre-wrap;
}
</style>
