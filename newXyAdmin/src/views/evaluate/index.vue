<template>
  <div>
    <div class="view__header">
      <h2 class="view__title">评估管理</h2>
      <p class="view__subtitle">查看评估记录和配置评估开关</p>
    </div>
    <el-tabs v-model="activeTab" class="merged-tabs">
      <el-tab-pane label="评估记录" name="list">
        <EvaluateList />
      </el-tab-pane>
      <el-tab-pane label="评估配置" name="config">
        <EvaluateConfig />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import EvaluateList from './EvaluateList.vue'
import EvaluateConfig from './EvaluateConfig.vue'

const activeTab = ref('list')
</script>

<style scoped>
.merged-tabs { margin-top: -8px; }
</style>
