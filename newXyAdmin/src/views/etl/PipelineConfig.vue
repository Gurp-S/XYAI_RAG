<template>
  <div class="view-flex">
    <div class="view__header">
      <h2 class="view__title">ETL 流水线配置</h2>
      <p class="view__subtitle">配置文档处理流水线</p>
    </div>

    <el-row :gutter="16">
      <el-col :span="12">
        <el-card shadow="never">
          <template #header>流水线参数</template>
          <el-form label-width="140px" size="default">
            <el-form-item label="启用 Enricher">
              <el-switch v-model="config.enricherEnable" />
            </el-form-item>
            <el-form-item label="默认 Chunk 大小">
              <el-input-number v-model="config.defaultChunkSize" :min="100" :step="100" />
            </el-form-item>
            <el-form-item label="默认 Overlap">
              <el-input-number v-model="config.defaultOverlapSize" :min="0" :step="10" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="saveConfig" :loading="saving">保存配置</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card shadow="never">
          <template #header>节点配置</template>
          <div v-if="nodes.length === 0" style="color: var(--text-secondary); padding: 20px; text-align: center;">加载中...</div>
          <div v-for="(node, idx) in nodes" :key="node.nodeType" class="node-item">
            <div class="node-item__header">
              <div class="node-item__order">#{{ idx + 1 }}</div>
              <div class="node-item__info">
                <div class="node-item__name">{{ node.label || node.nodeType }}</div>
                <div class="node-item__type">{{ node.nodeType }}</div>
              </div>
              <el-switch v-model="node.enabled" size="small" />
            </div>
            <div v-if="node.nextNodeType" class="node-item__next">
              <el-icon><Right /></el-icon> 下一节点: {{ node.nextNodeType }}
            </div>
          </div>
          <el-button type="primary" size="small" @click="saveNodes" :loading="savingNodes" style="margin-top: 16px">
            保存节点配置
          </el-button>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as etlApi from '../../api/etl'

const config = reactive({ enricherEnable: false, defaultChunkSize: 500, defaultOverlapSize: 50 })
const nodes = ref([])
const saving = ref(false)
const savingNodes = ref(false)

async function load() {
  try {
    const res = await etlApi.getPipelineConfig()
    config.enricherEnable = res.enricherEnable ?? false
    config.defaultChunkSize = res.defaultChunkSize ?? 500
    config.defaultOverlapSize = res.defaultOverlapSize ?? 50
    nodes.value = (res.nodes || []).map(n => ({ ...n, enabled: n.enabled !== false }))
  } catch (e) { /* ignore */ }
}

async function saveConfig() {
  saving.value = true
  try {
    await etlApi.updatePipeline({
      enricherEnable: config.enricherEnable,
      defaultChunkSize: config.defaultChunkSize,
      defaultOverlapSize: config.defaultOverlapSize
    })
    ElMessage.success('参数已保存')
  } catch (e) { /* handled */ }
  finally { saving.value = false }
}

async function saveNodes() {
  savingNodes.value = true
  try {
    await etlApi.updatePipeline({}, { nodes: nodes.value.map(n => ({ nodeType: n.nodeType, enabled: n.enabled })) })
    ElMessage.success('节点配置已保存')
  } catch (e) { /* handled */ }
  finally { savingNodes.value = false }
}

onMounted(load)
</script>

<style scoped>
.node-item {
  background: var(--bg-hover);
  border-radius: var(--radius-md);
  padding: 12px 16px;
  margin-bottom: 8px;
  transition: background-color 0.2s;
}
.node-item__header {
  display: flex;
  align-items: center;
  gap: 12px;
}
.node-item__order {
  width: 28px; height: 28px;
  border-radius: 50%;
  background: var(--primary-lighter);
  color: var(--primary);
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 600;
  flex-shrink: 0;
}
.node-item__info { flex: 1; }
.node-item__name { font-size: 14px; font-weight: 500; color: var(--text-primary); }
.node-item__type { font-size: 11px; color: var(--text-secondary); }
.node-item__next { font-size: 12px; color: var(--text-secondary); margin-top: 6px; display: flex; align-items: center; gap: 4px; }
</style>
