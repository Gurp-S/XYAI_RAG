<template>
  <div class="view-flex">
    <div class="view__header">
      <h2 class="view__title">文件管理</h2>
      <p class="view__subtitle">管理向量库中的文件</p>
    </div>

    <el-card shadow="never" class="card-filter">
      <el-form :inline="true" size="default">
        <el-form-item label="文件 ID">
          <el-input v-model="filterFileId" clearable placeholder="模糊搜索" style="width: 200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="filterFileId = ''; load()">重置</el-button>
        </el-form-item>
        <el-form-item style="float: right">
          <el-button type="danger" plain size="small" @click="cleanUnused">清理未使用文件</el-button>
          <el-button type="warning" plain size="small" @click="processFiles">处理孤儿文件</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="card-table">
      <el-table :data="data" stripe v-loading="loading" @sort-change="handleSortChange" :max-height="tableHeight">
        <el-table-column prop="fileId" label="文件 ID" min-width="280" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="collections" label="所属集合" min-width="200" show-overflow-tooltip />
        <el-table-column prop="useCount" label="使用次数" width="110" sortable="custom" align="center">
          <template #default="{ row }">{{ formatNumber(row.useCount) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="showUsers(row)">查看用户</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-footer">
        <el-pagination
          v-model:current-page="page" v-model:page-size="size"
          :total="total" :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="handlePageChange" @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- Users Dialog -->
    <el-dialog v-model="userDialog.visible" title="文件用户" width="500px">
      <template v-if="userDialog.fileId">
        <p style="color: var(--text-secondary); margin-bottom: 12px;">
          文件: <strong style="color: var(--text-primary);">{{ userDialog.fileId }}</strong>
        </p>
        <el-table :data="userDialog.users" stripe size="small" max-height="400">
          <el-table-column prop="id" label="用户 ID" width="100" />
          <el-table-column prop="name" label="用户名" min-width="150" />
          <el-table-column prop="userRank" label="等级" width="80" />
        </el-table>
        <div class="table-footer">
          <el-pagination
            v-model:current-page="userDialog.page" :page-size="userDialog.size"
            :total="userDialog.total" layout="total, prev, pager, next"
            @current-change="loadFileUsers" small
          />
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as milvusApi from '../../api/milvus'
import { formatNumber } from '../../utils/format'
import { usePagination } from '../../composables/usePagination'
import { useTableHeight } from '../../composables/useLayout'

const {
  page, size, total, data, loading,
  load, handlePageChange, handleSizeChange, handleSortChange
} = usePagination(milvusApi.getFilesPaged, 'useCount', 'desc')

const filterFileId = ref('')
const { tableHeight } = useTableHeight(80)

const userDialog = reactive({
  visible: false, fileId: '', users: [], page: 1, size: 10, total: 0
})

function handleSearch() { load({ fileId: filterFileId.value }) }

async function showUsers(row) {
  userDialog.fileId = row.fileId
  userDialog.page = 1
  userDialog.visible = true
  await loadFileUsers(1)
}

async function loadFileUsers(p) {
  userDialog.page = p
  try {
    const res = await milvusApi.getFileUsersPaged({
      fileId: userDialog.fileId,
      page: userDialog.page, size: userDialog.size
    })
    userDialog.users = res.records || []
    userDialog.total = res.total || 0
  } catch (e) { /* ignore */ }
}

async function cleanUnused() {
  try {
    await milvusApi.cleanUnusedFiles()
    ElMessage.success('未使用文件已清理')
    load()
  } catch (e) { /* handled */ }
}

async function processFiles() {
  try {
    await milvusApi.processorFiles()
    ElMessage.success('处理完成')
    load()
  } catch (e) { /* handled */ }
}

onMounted(load)
</script>

<style scoped>
.mb-4 { margin-bottom: 16px; }
.table-footer { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
