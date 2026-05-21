<template>
  <div>
    <el-card shadow="never">
      <el-table :data="featureList" stripe v-loading="loading">
        <el-table-column label="功能" min-width="220">
          <template #default="{ row }">
            <div>
              <span style="font-weight: 500">{{ row.display }}</span>
              <el-tag v-if="!row.modelName" size="small" type="warning" style="margin-left: 8px">未设置</el-tag>
            </div>
            <div style="font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px">
              {{ row.feature }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="模型" min-width="260">
          <template #default="{ row }">
            <el-select
              v-model="row.modelName"
              filterable
              placeholder="选择模型"
              style="width: 100%"
              @change="val => handleChange(row, val)"
            >
              <el-option
                v-for="m in modelNames"
                :key="m"
                :label="m"
                :value="m"
              />
            </el-select>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as llmApi from '../../api/llm'

/** 后端 SystemConfigService 中预定义的功能列表 */
const PREDEFINED_FEATURES = [
  { feature: 'chat_default', display: '默认对话模型' },
  { feature: 'mcp_decision', display: 'MCP工具决策模型' },
  { feature: 'pipeline_enhance', display: '管道增强模型' },
  { feature: 'intent_recognition', display: '意图识别模型' },
  { feature: 'rewrite', display: '查询重写模型' },
]

const featureList = ref([])
const modelNames = ref([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const [map, candidates] = await Promise.all([
      llmApi.getLLMFeatures(),
      llmApi.getCandidates().then(r => r.map(m => m.name)),
    ])
    modelNames.value = candidates

    const existing = map || {}
    // 合并预定义功能 + 后端已存在的额外功能
    featureList.value = PREDEFINED_FEATURES.map(f => ({
      ...f,
      modelName: existing[f.feature] || '',
    }))
    Object.entries(existing).forEach(([feature, modelName]) => {
      if (!PREDEFINED_FEATURES.some(f => f.feature === feature)) {
        featureList.value.push({ feature, display: feature, modelName })
      }
    })
  } catch (e) {
    featureList.value = []
  } finally {
    loading.value = false
  }
}

async function handleChange(row, modelName) {
  if (loading.value) return
  const prev = row._prevModel
  try {
    await llmApi.setLLMFeature(row.feature, modelName)
    row._prevModel = modelName
    ElMessage.success(`功能「${row.display}」→ ${modelName || '未设置'}`)
  } catch (e) {
    // 后端校验失败时回退到之前的值
    row.modelName = prev || ''
  }
}

onMounted(load)
</script>

<style scoped>
.toolbar { margin-bottom: 16px; }
</style>
