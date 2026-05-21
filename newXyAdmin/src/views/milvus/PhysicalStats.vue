<template>
  <div class="view-flex">
    <div class="view__header">
      <h2 class="view__title">物理统计</h2>
      <p class="view__subtitle">Milvus 集合物理存储统计</p>
    </div>

    <el-card shadow="never" class="card-table">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>集合物理信息</span>
          <el-button size="small" @click="load" :loading="loading">刷新</el-button>
        </div>
      </template>
      <el-table :data="stats" stripe v-loading="loading" :max-height="tableHeight">
        <el-table-column prop="collectionName" label="集合名称" min-width="240" />
        <el-table-column prop="databaseName" label="数据库" min-width="160" />
        <el-table-column prop="rowCount" label="行数" width="120" sortable="custom" align="right">
          <template #default="{ row }">{{ formatNumber(row.rowCount) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card shadow="never" class="mt-4">
      <template #header>批处理操作</template>
      <div style="display: flex; gap: 12px; flex-wrap: wrap;">
        <el-button @click="cleanAllUnusedCollections" :loading="cleaning1">清理未使用集合</el-button>
        <el-button @click="cleanAllCollections" :loading="cleaning2">清理空集合</el-button>
        <el-button @click="processAllFiles" :loading="cleaning3">处理未关联文件</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as milvusApi from '../../api/milvus'
import { formatNumber } from '../../utils/format'
import { useTableHeight } from '../../composables/useLayout'

const stats = ref([])
const loading = ref(false)
const cleaning1 = ref(false)
const cleaning2 = ref(false)
const cleaning3 = ref(false)
const { tableHeight } = useTableHeight(100)

async function load() {
  loading.value = true
  try { stats.value = await milvusApi.getPhysicalCollectionStats() }
  catch (e) { stats.value = [] }
  finally { loading.value = false }
}

async function cleanAllUnusedCollections() {
  cleaning1.value = true
  try { await milvusApi.cleanUnusedCollections(); ElMessage.success('已清理未使用集合') }
  catch (e) { /* handled */ }
  finally { cleaning1.value = false; load() }
}

async function cleanAllCollections() {
  cleaning2.value = true
  try { await milvusApi.processorCollections(); ElMessage.success('已清理空集合') }
  catch (e) { /* handled */ }
  finally { cleaning2.value = false; load() }
}

async function processAllFiles() {
  cleaning3.value = true
  try { await milvusApi.processorFiles(); ElMessage.success('已处理') }
  catch (e) { /* handled */ }
  finally { cleaning3.value = false }
}

onMounted(load)
</script>

<style scoped>
.mt-4 { margin-top: 16px; }
</style>
