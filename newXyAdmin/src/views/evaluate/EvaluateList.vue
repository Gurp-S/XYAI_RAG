<template>
  <div class="view-flex">
    <div class="view__header">
      <h2 class="view__title">评估记录</h2>
      <p class="view__subtitle">查看和删除评估结果</p>
    </div>

    <el-card shadow="never" class="card-filter">
      <el-form :inline="true" size="default">
        <el-form-item label="模型">
          <el-select v-model="filterModel" clearable placeholder="全部" style="width: 180px">
            <el-option v-for="m in modelNames" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="filterModel = ''; load()">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="card-table">
      <el-table :data="data" stripe v-loading="loading" @sort-change="handleSortChange" :max-height="tableHeight">
        <el-table-column prop="chatMessageId" label="消息 ID" min-width="200" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="modelName" label="模型" width="150" sortable="custom" />
        <el-table-column prop="ruleScore" label="规则分" width="100" sortable="custom" align="center">
          <template #default="{ row }"><span :class="scoreClass(row.ruleScore)">{{ row.ruleScore ?? '-' }}</span></template>
        </el-table-column>
        <el-table-column prop="rerankScore" label="重排分" width="100" sortable="custom" align="center">
          <template #default="{ row }"><span :class="scoreClass(row.rerankScore)">{{ row.rerankScore ?? '-' }}</span></template>
        </el-table-column>
        <el-table-column prop="llmScore" label="LLM 分" width="100" sortable="custom" align="center">
          <template #default="{ row }"><span :class="scoreClass(row.llmScore)">{{ row.llmScore ?? '-' }}</span></template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="170" sortable="custom" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-popconfirm title="确认删除？" @confirm="handleDelete(row.chatMessageId)">
              <template #reference>
                <el-button size="small" text type="danger">删除</el-button>
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
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as evaluateApi from '../../api/evaluate'
import { formatNumber } from '../../utils/format'
import { usePagination } from '../../composables/usePagination'
import { useTableHeight } from '../../composables/useLayout'

const {
  page, size, total, data, loading,
  load, handlePageChange, handleSizeChange, handleSortChange
} = usePagination(evaluateApi.getEvaluateList, 'createTime', 'desc')

const filterModel = ref('')
const modelNames = ref([])
const { tableHeight } = useTableHeight(80)

function handleSearch() { load({ modelName: filterModel.value }) }

function scoreClass(score) {
  if (score === null || score === undefined) return 'text-info'
  if (score >= 0.8) return 'text-success'
  if (score >= 0.6) return 'text-primary'
  if (score >= 0.4) return 'text-warning'
  return 'text-danger'
}

async function handleDelete(chatMessageId) {
  try {
    await evaluateApi.deleteEvaluate(chatMessageId)
    ElMessage.success('已删除')
    load()
  } catch (e) { /* handled */ }
}

onMounted(async () => {
  load()
  try { modelNames.value = await evaluateApi.getEvaluateModelNames() } catch (e) { /* ignore */ }
})
</script>

<style scoped>
.mb-4 { margin-bottom: 16px; }
.table-footer { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
