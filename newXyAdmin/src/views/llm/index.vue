<template>
  <div class="view-flex">
    <div class="view__header">
      <h2 class="view__title">模型管理</h2>
      <p class="view__subtitle">管理 LLM 模型候选、特性映射和系统配置</p>
    </div>

    <div class="card-table">
      <el-tabs v-model="activeTab" class="llm-tabs">
      <el-tab-pane label="模型候选" name="candidates">
        <ModelCandidates />
      </el-tab-pane>
      <el-tab-pane label="特性-模型映射" name="features">
        <FeatureMapping />
      </el-tab-pane>
      <el-tab-pane label="系统配置" name="config">
        <SystemConfigSection />
      </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import ModelCandidates from './ModelCandidates.vue'
import FeatureMapping from './FeatureMapping.vue'
import SystemConfigSection from './SystemConfigSection.vue'

const activeTab = ref('candidates')
</script>

<style scoped>
.llm-tabs {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.llm-tabs :deep(.el-tabs__content) {
  flex: 1;
  overflow: hidden;
  min-height: 0;
}
.llm-tabs :deep(.el-tab-pane) {
  height: 100%;
  overflow-y: auto;
}
</style>
