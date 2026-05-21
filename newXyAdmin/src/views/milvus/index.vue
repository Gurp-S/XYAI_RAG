<template>
  <div>
    <div class="view__header">
      <h2 class="view__title">向量库管理</h2>
      <p class="view__subtitle">管理集合和文件</p>
    </div>
    <el-tabs v-model="activeTab" class="merged-tabs">
      <el-tab-pane label="集合管理" name="collections">
        <CollectionsView />
      </el-tab-pane>
      <el-tab-pane label="文件管理" name="files">
        <FilesView />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import CollectionsView from './Collections.vue'
import FilesView from './Files.vue'

const activeTab = ref('collections')
</script>

<style scoped>
.merged-tabs { margin-top: -8px; }
</style>
