<template>
  <div class="view-flex">
    <div class="view__header">
      <h2 class="view__title">调用链追踪</h2>
      <p class="view__subtitle">查看和检索调用链信息</p>
    </div>

    <el-card shadow="never" class="card-filter">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="调用链列表" name="traces">
          <el-form :inline="true" size="default" class="mt-2">
            <el-form-item label="名称">
              <el-select v-model="traceFilter.name" clearable filterable placeholder="全部" style="width: 200px">
                <el-option v-for="n in rootNames" :key="n" :label="n" :value="n" />
              </el-select>
            </el-form-item>
            <el-form-item label="开始">
              <el-date-picker v-model="traceFilter.startTime" type="datetime" placeholder="开始时间" style="width: 180px" />
            </el-form-item>
            <el-form-item label="结束">
              <el-date-picker v-model="traceFilter.endTime" type="datetime" placeholder="结束时间" style="width: 180px" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="searchTraces">查询</el-button>
              <el-button @click="resetTraces">重置</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>
        <el-tab-pane label="节点列表" name="nodes">
          <el-form :inline="true" size="default" class="mt-2">
            <el-form-item label="节点名">
              <el-select v-model="nodeFilter.nodeName" clearable filterable placeholder="全部" style="width: 200px">
                <el-option v-for="n in nodeNames" :key="n" :label="n" :value="n" />
              </el-select>
            </el-form-item>
            <el-form-item label="Trace ID">
              <el-input v-model="nodeFilter.traceId" placeholder="模糊搜索" clearable style="width: 200px" />
            </el-form-item>
            <el-form-item label="开始">
              <el-date-picker v-model="nodeFilter.startTime" type="datetime" placeholder="开始时间" style="width: 180px" />
            </el-form-item>
            <el-form-item label="结束">
              <el-date-picker v-model="nodeFilter.endTime" type="datetime" placeholder="结束时间" style="width: 180px" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="searchNodes">查询</el-button>
              <el-button @click="resetNodes">重置</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- Traces Table -->
    <el-card v-show="activeTab === 'traces'" shadow="never" class="card-table">
      <el-table :data="traceData" stripe v-loading="traceLoading" @sort-change="traceSortChange" :max-height="tableHeight">>
        <el-table-column prop="traceId" label="Trace ID" min-width="240" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="name" label="根名称" width="160" sortable="custom" />
        <el-table-column prop="startTime" label="开始时间" width="170" sortable="custom" />
        <el-table-column prop="endTime" label="结束时间" width="170" sortable="custom" />
        <el-table-column prop="coseTime" label="耗时" width="100" sortable="custom" align="right">
          <template #default="{ row }">
            <span :class="durationClass(row.coseTime)">{{ formatDuration(row.coseTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status || '-' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="viewDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-footer">
        <el-pagination
          v-model:current-page="tracePage" v-model:page-size="traceSize"
          :total="traceTotal" :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadTraces" @size-change="traceSizeChange"
        />
      </div>
    </el-card>

    <!-- Nodes Table -->
    <el-card v-show="activeTab === 'nodes'" shadow="never" class="card-table">
      <el-table :data="nodeData" stripe v-loading="nodeLoading" @sort-change="nodeSortChange" :max-height="tableHeight">>
        <el-table-column prop="traceId" label="Trace ID" min-width="240" show-overflow-tooltip sortable="custom" />
        <el-table-column prop="nodeName" label="节点名" width="160" sortable="custom" />
        <el-table-column prop="startTime" label="开始时间" width="170" sortable="custom" />
        <el-table-column prop="endTime" label="结束时间" width="170" sortable="custom" />
        <el-table-column prop="costTime" label="耗时(ms)" width="100" sortable="custom" align="right">
          <template #default="{ row }">
            <span :class="durationClass(row.costTime)">{{ formatDuration(row.costTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-footer">
        <el-pagination
          v-model:current-page="nodePage" v-model:page-size="nodeSize"
          :total="nodeTotal" :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="loadNodes" @size-change="nodeSizeChange"
        />
      </div>
    </el-card>

    <!-- Detail Dialog -->
    <el-dialog v-model="detailVisible" title="调用链详情" width="800px" top="4vh">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" class="mb-3">
          <el-descriptions-item label="Trace ID" :span="2">
            <span style="word-break: break-all;">{{ detail.trace?.traceId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="根名称" :span="2">{{ detail.trace?.name }}</el-descriptions-item>
          <el-descriptions-item label="耗时">
            <span :class="durationClass(detail.trace?.coseTime)">{{ formatDuration(detail.trace?.coseTime) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusType(detail.trace?.status)" size="small">{{ detail.trace?.status || '-' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="开始时间">{{ detail.trace?.startTime }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ detail.trace?.endTime }}</el-descriptions-item>
        </el-descriptions>
        <el-descriptions v-if="detail.trace?.status === 'ERROR' || detail.trace?.errorMessage" :column="1" border size="small" class="mb-3" style="margin-top:12px">
          <el-descriptions-item label="错误信息">
            <span style="color:var(--danger);word-break:break-all;">{{ detail.trace?.errorMessage }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <h4 style="margin: 16px 0 8px; color: var(--text-primary);">节点列表</h4>
        <div class="detail-nodes">
          <el-table :data="detail.nodes" stripe size="small" max-height="400" style="width:100%">
            <el-table-column prop="nodeName" label="节点名" min-width="140" />
            <el-table-column prop="nodeType" label="类型" width="100">
              <template #default="{ row }">
                <el-tag size="small" type="info" effect="plain">{{ row.nodeType }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="startTime" label="开始时间" width="155" />
            <el-table-column prop="costTime" label="耗时" width="90" align="right">
              <template #default="{ row }">
                <span :class="durationClass(row.costTime)">{{ formatDuration(row.costTime) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import * as traceApi from '../../api/traceInfo'
import { formatNumber, formatDuration, durationClass, statusType } from '../../utils/format'
import { useTableHeight } from '../../composables/useLayout'

const activeTab = ref('traces')

// Filters
const traceFilter = reactive({ name: '', startTime: null, endTime: null })
const nodeFilter = reactive({ nodeName: '', traceId: '', startTime: null, endTime: null })
const rootNames = ref([])
const nodeNames = ref([])

// Trace pagination
const traceData = ref([]); const traceTotal = ref(0)
const tracePage = ref(1); const traceSize = ref(20)
const traceLoading = ref(false)
let traceSortBy = 'startTime'; let traceSortOrder = 'desc'

// Node pagination
const nodeData = ref([]); const nodeTotal = ref(0)
const nodePage = ref(1); const nodeSize = ref(20)
const nodeLoading = ref(false)
let nodeSortBy = 'startTime'; let nodeSortOrder = 'desc'
const { tableHeight } = useTableHeight(60)

// Detail
const detailVisible = ref(false)
const detail = ref(null)

async function loadTraces(p) {
  if (p) tracePage.value = p
  traceLoading.value = true
  try {
    const params = {
      page: tracePage.value, size: traceSize.value,
      sortBy: traceSortBy, sortOrder: traceSortOrder
    }
    if (traceFilter.name) params.name = traceFilter.name
    if (traceFilter.startTime) params.startTime = traceFilter.startTime
    if (traceFilter.endTime) params.endTime = traceFilter.endTime
    const res = await traceApi.getTracesPaged(params)
    traceData.value = res.records || []
    traceTotal.value = res.total || 0
  } catch (e) { /* ignore */ }
  finally { traceLoading.value = false }
}

function traceSizeChange(s) { traceSize.value = s; tracePage.value = 1; loadTraces() }
function traceSortChange({ prop, order }) {
  traceSortBy = prop || 'startTime'
  traceSortOrder = order === 'ascending' ? 'asc' : 'desc'
  tracePage.value = 1; loadTraces()
}
function searchTraces() { tracePage.value = 1; loadTraces() }
function resetTraces() {
  traceFilter.name = ''; traceFilter.startTime = null; traceFilter.endTime = null
  tracePage.value = 1; loadTraces()
}

async function loadNodes(p) {
  if (p) nodePage.value = p
  nodeLoading.value = true
  try {
    const params = {
      page: nodePage.value, size: nodeSize.value,
      sortBy: nodeSortBy, sortOrder: nodeSortOrder
    }
    if (nodeFilter.nodeName) params.nodeName = nodeFilter.nodeName
    if (nodeFilter.traceId) params.traceId = nodeFilter.traceId
    if (nodeFilter.startTime) params.startTime = nodeFilter.startTime
    if (nodeFilter.endTime) params.endTime = nodeFilter.endTime
    const res = await traceApi.getNodesPaged(params)
    nodeData.value = res.records || []
    nodeTotal.value = res.total || 0
  } catch (e) { /* ignore */ }
  finally { nodeLoading.value = false }
}

function nodeSizeChange(s) { nodeSize.value = s; nodePage.value = 1; loadNodes() }
function nodeSortChange({ prop, order }) {
  nodeSortBy = prop || 'startTime'
  nodeSortOrder = order === 'ascending' ? 'asc' : 'desc'
  nodePage.value = 1; loadNodes()
}
function searchNodes() { nodePage.value = 1; loadNodes() }
function resetNodes() {
  nodeFilter.nodeName = ''; nodeFilter.traceId = ''
  nodeFilter.startTime = null; nodeFilter.endTime = null
  nodePage.value = 1; loadNodes()
}

async function viewDetail(row) {
  try {
    detail.value = await traceApi.getTraceDetail(row.traceId)
    detailVisible.value = true
  } catch (e) { /* ignore */ }
}

onMounted(async () => {
  loadTraces()
  loadNodes()
  try { rootNames.value = await traceApi.getAllTraceRootNames() || [] } catch (e) { /* ignore */ }
  try { nodeNames.value = await traceApi.getAllTraceNodeNames() || [] } catch (e) { /* ignore */ }
})
</script>

<style scoped>
.mb-4 { margin-bottom: 16px; }
.mt-2 { margin-top: 4px; }
.mb-3 { margin-bottom: 12px; }
.table-footer { margin-top: 16px; display: flex; justify-content: flex-end; }
.detail-nodes { width: 100%; overflow: hidden; }
.detail-nodes :deep(.el-table__body-wrapper) { overflow-x: hidden; }
</style>
