<template>
  <div class="view-flex">
    <div class="view__header">
      <h2 class="view__title">评估配置</h2>
      <p class="view__subtitle">配置评估功能的开关</p>
    </div>

    <el-card shadow="never">
      <el-form label-width="140px" size="default">
        <el-form-item label="规则评估">
          <el-switch v-model="config.ruleEnabled" />
          <div class="form-help">基于规则的自动评估</div>
        </el-form-item>
        <el-form-item label="重排评估">
          <el-switch v-model="config.rerankEnabled" />
          <div class="form-help">基于重排模型的评估</div>
        </el-form-item>
        <el-form-item label="LLM 评估">
          <el-switch v-model="config.llmEnabled" />
          <div class="form-help">基于大语言模型的评估</div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="saveConfig" :loading="saving">保存配置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as evaluateApi from '../../api/evaluate'

const config = reactive({ ruleEnabled: false, rerankEnabled: false, llmEnabled: false })
const saving = ref(false)

async function load() {
  try {
    const res = await evaluateApi.getEvaluateConfig()
    if (res) {
      config.ruleEnabled = res.ruleEnabled ?? false
      config.rerankEnabled = res.rerankEnabled ?? false
      config.llmEnabled = res.llmEnabled ?? false
    }
  } catch (e) { /* ignore */ }
}

async function saveConfig() {
  saving.value = true
  try {
    await evaluateApi.setEvaluateConfig({
      ruleEnabled: config.ruleEnabled,
      rerankEnabled: config.rerankEnabled,
      llmEnabled: config.llmEnabled
    })
    ElMessage.success('配置已保存')
  } catch (e) { /* handled */ }
  finally { saving.value = false }
}

onMounted(load)
</script>

<style scoped>
.form-help { font-size: 12px; color: var(--text-secondary); margin-top: 4px; }
</style>
