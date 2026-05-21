<template>
  <div class="view-flex">
    <div class="view__header">
      <h2 class="view__title">Token 记录</h2>
      <p class="view__subtitle">查看 Token 消耗和使用统计</p>
    </div>

    <!-- Summary cards -->
    <el-row :gutter="16" class="mb-4">
      <el-col :span="6" v-for="item in summaryItems" :key="item.label">
        <el-card shadow="never" class="summary-card">
          <div class="summary-card__label">{{ item.label }}</div>
          <div class="summary-card__value">{{ item.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- Filters -->
    <el-card shadow="never" class="card-filter">
      <el-form :inline="true" :model="filters" size="default">
        <el-form-item label="模型">
          <el-select v-model="filters.modelName" clearable placeholder="全部模型" style="width: 180px">
            <el-option v-for="m in modelNames" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="用户 ID">
          <el-input v-model="filters.userId" placeholder="输入用户 ID" style="width: 160px" clearable />
        </el-form-item>
        <el-form-item label="调用类型">
          <el-select v-model="filters.callType" clearable placeholder="全部" style="width: 140px">
            <el-option label="chat" value="chat" />
            <el-option label="mcp" value="mcp" />
            <el-option label="fast" value="fast" />
            <el-option label="enhance" value="enhance" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- Table -->
    <el-card shadow="never" class="card-table">
      <el-table
        :data="data"
        stripe
        v-loading="loading"
        @sort-change="handleSortChange"
        style="width: 100%"
        :max-height="tableHeight"
      >
        <el-table-column prop="chatMessageId" label="消息 ID" min-width="200" show-overflow-tooltip />
        <el-table-column prop="conversationId" label="会话 ID" min-width="200" show-overflow-tooltip />
        <el-table-column prop="userId" label="用户 ID" width="100" sortable="custom" />
        <el-table-column prop="modelName" label="模型" width="140" sortable="custom" />
        <el-table-column prop="promptTokens" label="Prompt" width="100" sortable="custom" align="right" />
        <el-table-column prop="completionTokens" label="Completion" width="110" sortable="custom" align="right" />
        <el-table-column prop="totalTokens" label="总计" width="100" sortable="custom" align="right" />
        <el-table-column prop="costMs" label="耗时(ms)" width="110" sortable="custom" align="right">
          <template #default="{ row }">
            <span :class="durationClass(row.costMs)">{{ row.costMs }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="callType" label="类型" width="100">
          <template #default="{ row }">
            <el-tag :type="callTypeTag(row.callType)" size="small">{{ row.callType }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="时间" width="170" sortable="custom" />
      </el-table>
      <div class="table-footer">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, computed } from 'vue'
import * as chatApi from '../../api/chat'
import { usePagination } from '../../composables/usePagination'
import { formatNumber, durationClass } from '../../utils/format'
import { useTableHeight } from '../../composables/useLayout'

const {
  page, size, total, data, loading,
  load, handlePageChange, handleSizeChange, handleSortChange
} = usePagination(chatApi.getTokenRecordsPaged, 'createdAt', 'desc')

const filters = reactive({ modelName: '', userId: '', callType: '' })
const modelNames = ref([])
const { tableHeight } = useTableHeight(80) // extra for summary cards + filter
const summaryItems = computed(() => [
  { label: '总 Tokens', value: formatNumber(summaryData.totalTokens) },
  { label: 'Prompt Tokens', value: formatNumber(summaryData.promptTokens) },
  { label: 'Completion Tokens', value: formatNumber(summaryData.completionTokens) },
  { label: '消息数', value: formatNumber(summaryData.messageCount) }
])

const summaryData = reactive({ totalTokens: 0, promptTokens: 0, completionTokens: 0, messageCount: 0 })

function callTypeTag(type) {
  const map = { chat: 'primary', mcp: 'success', fast: 'warning', enhance: 'info' }
  return map[type] || 'info'
}

function handleSearch() {
  load({ ...filters })
}

function handleReset() {
  filters.modelName = ''
  filters.userId = ''
  filters.callType = ''
  load()
}

async function loadSummary() {
  try {
    const res = await chatApi.getAllToken()
    Object.assign(summaryData, res)
  } catch (e) { /* ignore */ }
}

async function loadModelNames() {
  try {
    modelNames.value = await chatApi.getModelNames()
  } catch (e) { /* ignore */ }
}

onMounted(() => {
  load()
  loadSummary()
  loadModelNames()
})
</script>

<style scoped>
.mb-4 { margin-bottom: 16px; }
.summary-card { text-align: center; }
.summary-card__label { font-size: 12px; color: var(--text-secondary); margin-bottom: 4px; }
.summary-card__value { font-size: 24px; font-weight: 600; color: var(--text-primary); }
.table-footer { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
