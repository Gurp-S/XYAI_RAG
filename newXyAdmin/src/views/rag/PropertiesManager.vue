<template>
  <div class="rag-panel">
    <div class="rag-panel__tabs">
      <el-tabs v-model="activeModule" @tab-click="loadModule">
        <el-tab-pane v-for="mod in modules" :key="mod.key" :label="mod.label" :name="mod.key" />
      </el-tabs>
    </div>

    <div v-loading="loading" style="min-height: 60px;">
      <div v-if="!currentProps || Object.keys(currentProps).length === 0" class="rag-panel__empty">
        暂无配置
      </div>
      <div v-else class="rag-panel__grid">
        <div v-for="(value, key) in currentProps" :key="key" class="rag-panel__item">
          <span class="rag-panel__label">{{ labelMap[key] || key }}</span>
          <span class="rag-panel__ctrl">
            <el-switch
              v-if="typeof value === 'boolean'"
              v-model="currentProps[key]"
              size="small"
            />
            <el-input
              v-else-if="typeof value === 'number'"
              v-model.number="currentProps[key]"
              type="number"
              size="small"
              style="width: 100px"
            />
            <el-input
              v-else
              v-model="currentProps[key]"
              size="small"
              style="width: 130px"
            />
          </span>
        </div>
      </div>
      <div v-if="currentProps && Object.keys(currentProps).length > 0" class="rag-panel__actions">
        <el-button type="primary" size="small" @click="saveModule" :loading="saving">保存</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as ragApi from '../../api/ragProperties'

const modules = [
  { key: 'pipeline', label: '流水线' },
  { key: 'upload', label: '上传' },
  { key: 'intent', label: '意图' },
  { key: 'memory', label: '记忆' },
  { key: 'retrieval', label: '检索' },
  { key: 'rewriter', label: '重写' },
  { key: 'router', label: '路由' },
  { key: 'cache', label: '缓存' },
  { key: 'evaluate', label: '评估' },
  { key: 'tool', label: '工具' }
]

const activeModule = ref('pipeline')
const propsStore = reactive({})   // { pipeline: { key: value }, upload: {...}, ... }
const labelStore = reactive({})   // { pipeline_labels: {...}, ... }
const loading = ref(false)
const saving = ref(false)

const currentProps = computed(() => propsStore[activeModule.value] || null)
const labelMap = computed(() => labelStore[`${activeModule.value}_labels`] || {})

async function loadAll() {
  loading.value = true
  try {
    const data = await ragApi.getAllProperties()
    if (!data) return
    // Store each module's props and labels separately for stable reactivity
    for (const mod of modules) {
      if (data[mod.key] && typeof data[mod.key] === 'object') {
        propsStore[mod.key] = reactive({ ...data[mod.key] })
      }
      const labelKey = `_labels_${mod.key}`
      if (data[labelKey]) {
        labelStore[`${mod.key}_labels`] = { ...data[labelKey] }
      }
    }
  } catch (e) { /* ignore */ }
  finally { loading.value = false }
}

function loadModule() {
  // computed will reactively update currentProps
}

async function saveModule() {
  const props = currentProps.value
  if (!props) return
  saving.value = true
  try {
    await ragApi.updateProperties(activeModule.value, { ...props })
    ElMessage.success('配置已保存')
  } catch (e) { /* handled */ }
  finally { saving.value = false }
}

onMounted(loadAll)
</script>

<style scoped>
.rag-panel__empty {
  text-align: center; padding: 24px; color: var(--text-secondary); font-size: 13px;
}
.rag-panel__grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 8px;
}
.rag-panel__item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  background: var(--bg-hover);
  border-radius: var(--radius-sm);
  gap: 8px;
}
.rag-panel__label {
  font-size: 12px;
  color: var(--text-regular);
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rag-panel__ctrl { flex-shrink: 0; }
.rag-panel__actions { margin-top: 12px; text-align: right; }
.rag-panel__tabs { margin-bottom: 4px; }
.rag-panel__tabs :deep(.el-tabs__header) { margin-bottom: 12px; }
.rag-panel__tabs :deep(.el-tabs__nav-wrap) { margin-bottom: -1px; }
</style>
