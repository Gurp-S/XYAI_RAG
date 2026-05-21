<template>
  <div>
    <el-card shadow="never">
      <div class="toolbar">
        <el-button type="primary" @click="openAdd">
          <el-icon><Plus /></el-icon> 新增模型
        </el-button>
      </div>
      <el-table :data="data" stripe v-loading="loading">
        <el-table-column prop="name" label="名称" width="140" />
        <el-table-column prop="displayName" label="显示名称" width="140" />
        <el-table-column prop="apiModel" label="API 模型" min-width="160" />
        <el-table-column prop="priority" label="优先级" width="70" align="center" sortable="custom" />
        <el-table-column prop="temperature" label="温度" width="70" align="center" />
        <el-table-column prop="maxTokens" label="Max Tokens" width="100" align="right" />
        <el-table-column prop="enabled" label="状态" width="70" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="toggleEnabled(row)" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="健康状态" width="200">
          <template #default="{ row }">
            <div v-if="healthMap[row.name]" class="health-cell">
              <el-tag
                :type="healthMap[row.name].healthy ? 'success' : 'danger'"
                size="small"
                effect="dark"
              >
                {{ healthMap[row.name].healthy ? '健康' : '异常' }}
              </el-tag>
              <span class="health-stat">{{ healthMap[row.name].successRate }}%</span>
              <span class="health-stat health-stat--latency">{{ healthMap[row.name].avgLatency }}ms</span>
            </div>
            <span v-else style="color: var(--el-text-color-secondary); font-size: 12px;">未检测</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="checkHealth(row)">详情</el-button>
            <el-button size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
            <el-popconfirm title="确认删除？" @confirm="handleDelete(row.name)">
              <template #reference>
                <el-button size="small" text type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Add/Edit Dialog -->
    <el-dialog v-model="form.visible" :title="form.isEdit ? '编辑模型' : '新增模型'" width="480px">
      <el-form :model="form" label-width="100px" size="default">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" :disabled="form.isEdit" />
        </el-form-item>
        <el-form-item label="显示名称">
          <el-input v-model="form.displayName" />
        </el-form-item>
        <el-form-item label="API 模型">
          <el-input v-model="form.apiModel" />
        </el-form-item>
        <el-form-item label="优先级">
          <el-input-number v-model="form.priority" :min="1" :max="10" />
        </el-form-item>
        <el-form-item label="温度">
          <el-slider v-model="form.temperature" :min="0" :max="2" :step="0.1" style="width: 180px" />
        </el-form-item>
        <el-form-item label="Max Tokens">
          <el-input-number v-model="form.maxTokens" :min="100" :step="100" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="form.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmForm" :loading="form.submitting">确认</el-button>
      </template>
    </el-dialog>

    <!-- Health Dialog -->
    <el-dialog v-model="health.visible" title="模型健康状态" width="440px">
      <template v-if="health.data">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="名称">{{ health.data.name }}</el-descriptions-item>
          <el-descriptions-item label="启用">
            <el-tag :type="health.data.enabled ? 'success' : 'info'" size="small">{{ health.data.enabled ? '是' : '否' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="健康">
            <el-tag :type="health.data.healthy ? 'success' : 'danger'" size="small">{{ health.data.healthy ? '健康' : '异常' }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="熔断器">{{ health.data.breakerState || '-' }}</el-descriptions-item>
          <el-descriptions-item label="成功率">{{ health.data.successRate }}%</el-descriptions-item>
          <el-descriptions-item label="平均延迟">{{ health.data.avgLatency }}ms</el-descriptions-item>
          <el-descriptions-item label="总调用">{{ health.data.totalCalls }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="health.data.error" style="margin-top: 8px;">
          <el-alert :title="'最近错误: ' + health.data.error" type="error" :closable="false" show-icon />
        </div>
        <div style="margin-top: 16px; text-align: right;">
          <el-button size="small" type="warning" @click="resetHealth(health.data.name)">重置状态</el-button>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as llmApi from '../../api/llm'

const data = ref([])
const loading = ref(false)

const form = reactive({
  visible: false, isEdit: false, submitting: false,
  name: '', displayName: '', apiModel: '', priority: 5, temperature: 0.3, maxTokens: 2000
})

const health = reactive({ visible: false, data: null })
const healthMap = ref({})

async function load() {
  loading.value = true
  try {
    const candidates = await llmApi.getCandidates()
    data.value = candidates
    // 并行加载所有健康状态
    try {
      const allHealth = await llmApi.getAllHealth()
      healthMap.value = allHealth || {}
    } catch (e) {
      // 健康数据非关键，静默降级
    }
  } catch (e) { /* ignore */ }
  finally { loading.value = false }
}

function openAdd() {
  form.isEdit = false
  form.name = ''; form.displayName = ''; form.apiModel = ''
  form.priority = 5; form.temperature = 0.3; form.maxTokens = 2000
  form.visible = true
}

function openEdit(row) {
  form.isEdit = true
  Object.assign(form, {
    name: row.name, displayName: row.displayName, apiModel: row.apiModel,
    priority: row.priority, temperature: row.temperature, maxTokens: row.maxTokens
  })
  form.visible = true
}

async function confirmForm() {
  if (!form.name) { ElMessage.warning('请输入名称'); return }
  form.submitting = true
  try {
    if (form.isEdit) {
      await llmApi.updateCandidate({
        name: form.name, displayName: form.displayName, apiModel: form.apiModel,
        priority: form.priority, temperature: form.temperature, maxTokens: form.maxTokens
      })
      ElMessage.success('模型已更新')
    } else {
      await llmApi.addCandidate({
        name: form.name, displayName: form.displayName, apiModel: form.apiModel,
        priority: form.priority, temperature: form.temperature, maxTokens: form.maxTokens
      })
      ElMessage.success('模型已添加')
    }
    form.visible = false
    load()
  } catch (e) { /* handled */ }
  finally { form.submitting = false }
}

async function handleDelete(name) {
  try {
    await llmApi.deleteCandidate(name)
    ElMessage.success('模型已删除')
    load()
  } catch (e) { /* handled */ }
}

async function toggleEnabled(row) {
  try {
    await llmApi.updateCandidate({ name: row.name, enabled: !row.enabled })
    ElMessage.success(row.enabled ? '模型已禁用' : '模型已启用')
    row.enabled = !row.enabled
  } catch (e) { /* handled */ }
}

async function checkHealth(row) {
  try {
    const res = await llmApi.getHealth(row.name)
    // getHealth 返回新版数字格式: successRate=95.67(百分比), avgLatency=150(毫秒)
    health.data = res
    health.visible = true
  } catch (e) { /* ignore */ }
}

async function resetHealth(name) {
  try {
    await llmApi.resetModel(name)
    ElMessage.success('健康状态已重置')
    health.visible = false
  } catch (e) { /* ignore */ }
}

onMounted(load)
</script>

<style scoped>
.mb-4 { margin-bottom: 16px; }
.toolbar { margin-bottom: 16px; }
.health-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.health-stat {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.health-stat--latency::before {
  content: '·';
  margin-right: 4px;
}
</style>
