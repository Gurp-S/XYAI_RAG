<template>
  <div class="view-flex">
    <div class="view__header">
      <h2 class="view__title">集合管理</h2>
      <p class="view__subtitle">管理向量库集合及用户权限</p>
    </div>

    <el-card shadow="never" class="card-filter">
      <el-form :inline="true" size="default">
        <el-form-item label="集合名">
          <el-input v-model="filterName" clearable placeholder="搜索集合" style="width: 200px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="filterName = ''; load()">重置</el-button>
        </el-form-item>
        <el-form-item style="float: right">
          <el-button @click="refreshCache" :loading="refreshing">刷新缓存</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="card-table">
      <el-table :data="data" stripe v-loading="loading" @sort-change="handleSortChange" :max-height="tableHeight">
        <el-table-column prop="name" label="集合名称" min-width="200" sortable="custom" />
        <el-table-column prop="fileCount" label="文件数" width="100" sortable="custom" align="center">
          <template #default="{ row }">{{ formatNumber(row.fileCount) }}</template>
        </el-table-column>
        <el-table-column prop="userCount" label="用户数" width="100" sortable="custom" align="center">
          <template #default="{ row }">{{ formatNumber(row.userCount) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="showUsers(row)">查看用户</el-button>
            <el-popconfirm title="清理空集合？" @confirm="cleanCollection(row.name)">
              <template #reference>
                <el-button size="small" text type="warning">清理</el-button>
              </template>
            </el-popconfirm>
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
    <el-dialog v-model="userDialog.visible" title="集合用户" width="500px">
      <template v-if="userDialog.collectionName">
        <p style="color: var(--text-secondary); margin-bottom: 12px;">
          集合: <strong style="color: var(--text-primary);">{{ userDialog.collectionName }}</strong>
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
            @current-change="loadCollectionUsers"
            small
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
} = usePagination(milvusApi.getCollectionsPaged, 'name', 'asc')

const filterName = ref('')
const refreshing = ref(false)
const { tableHeight } = useTableHeight(80)

const userDialog = reactive({
  visible: false, collectionName: '', users: [], page: 1, size: 10, total: 0
})

function handleSearch() { load({ name: filterName.value }) }

async function refreshCache() {
  refreshing.value = true
  try { await milvusApi.refreshCache(); ElMessage.success('缓存已刷新') }
  catch (e) { /* handled */ }
  finally { refreshing.value = false }
}

async function showUsers(row) {
  userDialog.collectionName = row.name
  userDialog.page = 1
  userDialog.visible = true
  await loadCollectionUsers(1)
}

async function loadCollectionUsers(p) {
  userDialog.page = p
  try {
    const res = await milvusApi.getCollectionUsersPaged({
      collectionName: userDialog.collectionName,
      page: userDialog.page, size: userDialog.size
    })
    userDialog.users = res.records || []
    userDialog.total = res.total || 0
  } catch (e) { /* ignore */ }
}

async function cleanCollection(name) {
  try {
    await milvusApi.processorCollections(name)
    ElMessage.success('集合已清理')
    load()
  } catch (e) { /* handled */ }
}

onMounted(load)
</script>

<style scoped>
.mb-4 { margin-bottom: 16px; }
.table-footer { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
