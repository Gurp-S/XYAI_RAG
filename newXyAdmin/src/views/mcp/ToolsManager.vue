<template>
  <div class="view-flex">
    <div class="view__header">
      <h2 class="view__title">MCP 工具管理</h2>
      <p class="view__subtitle">管理 MCP 工具的启用与禁用</p>
    </div>

    <el-card shadow="never" class="card-table">
      <el-table :data="tools" stripe v-loading="loading" @row-click="showDetail" :max-height="tableHeight">
        <el-table-column prop="name" label="工具名称" min-width="200" />
        <el-table-column prop="description" label="描述" min-width="300" show-overflow-tooltip />
        <el-table-column prop="enabled" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              :type="row.enabled ? 'warning' : 'success'"
              @click.stop="toggleTool(row)"
            >
              {{ row.enabled ? '禁用' : '启用' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Detail Dialog -->
    <el-dialog v-model="detailVisible" :title="detail?.name || '工具详情'" width="560px">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="名称">{{ detail.name }}</el-descriptions-item>
          <el-descriptions-item label="描述">{{ detail.description || '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="detail.enabled ? 'success' : 'info'" size="small">{{ detail.enabled ? '启用' : '禁用' }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>
        <div v-if="detail.inputSchema" class="mt-3">
          <h4 style="margin: 16px 0 8px; color: var(--text-primary); font-size: 14px;">输入 Schema</h4>
          <pre class="schema-block">{{ JSON.stringify(detail.inputSchema, null, 2) }}</pre>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as mcpApi from '../../api/mcp'
import { useTableHeight } from '../../composables/useLayout'

const tools = ref([])
const loading = ref(false)
const detailVisible = ref(false)
const { tableHeight } = useTableHeight()
const detail = ref(null)

async function load() {
  loading.value = true
  try { tools.value = await mcpApi.getAllTools() }
  catch (e) { /* ignore */ }
  finally { loading.value = false }
}

async function toggleTool(row) {
  try {
    if (row.enabled) {
      await mcpApi.disableTool(row.name)
    } else {
      await mcpApi.enableTool(row.name)
    }
    ElMessage.success(row.enabled ? '工具已禁用' : '工具已启用')
    row.enabled = !row.enabled
  } catch (e) { /* handled */ }
}

async function showDetail(row) {
  try {
    const res = await mcpApi.getMCPInfo(row.name)
    detail.value = res
    detailVisible.value = true
  } catch (e) { /* ignore */ }
}

onMounted(load)
</script>

<style scoped>
.mt-3 { margin-top: 12px; }
.schema-block {
  background: var(--bg-hover);
  border: 1px solid var(--border-color);
  border-radius: var(--radius-sm);
  padding: 12px;
  font-size: 12px;
  max-height: 300px;
  overflow: auto;
  white-space: pre-wrap;
  color: var(--text-regular);
}
</style>
