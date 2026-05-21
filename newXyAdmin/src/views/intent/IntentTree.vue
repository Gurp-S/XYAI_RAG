<template>
  <div class="view-flex">
    <div class="view__header">
      <h2 class="view__title">意图树</h2>
      <p class="view__subtitle">查看意图分类树结构</p>
    </div>

    <el-row :gutter="16">
      <el-col :span="16">
        <el-card shadow="never">
          <template #header>
            <div style="display: flex; align-items: center; gap: 12px;">
              <span>树结构</span>
              <el-button size="small" @click="loadTree" :loading="loading">刷新</el-button>
            </div>
          </template>
          <div v-if="treeData.length === 0" style="padding: 40px 0; text-align: center; color: var(--text-secondary);">
            暂无数据
          </div>
          <el-tree
            v-else
            :data="treeData"
            :props="treeProps"
            node-key="nodeName"
            highlight-current
            :default-expand-all="false"
            :expand-on-click-node="true"
            class="intent-tree"
          >
            <template #default="{ node, data }">
              <div class="intent-tree__node">
                <span class="intent-tree__name">{{ data.label || data.nodeName }}</span>
                <el-tag v-if="data.children_count === 0" size="small" type="info">叶子</el-tag>
              </div>
            </template>
          </el-tree>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card shadow="never">
          <template #header>叶子节点</template>
          <div v-if="leafNodes.length === 0" style="color: var(--text-secondary); text-align: center; padding: 20px;">
            暂无叶子节点
          </div>
          <div v-for="leaf in leafNodes" :key="leaf.nodeName" class="leaf-item">
            <el-icon><Document /></el-icon>
            <span>{{ leaf.label || leaf.nodeName }}</span>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import * as intentTreeApi from '../../api/intentTree'

const treeData = ref([])
const leafNodes = ref([])
const loading = ref(false)

const treeProps = {
  children: 'children',
  label: 'nodeName'
}

async function loadTree() {
  loading.value = true
  try { treeData.value = await intentTreeApi.getIntentTree() || [] }
  catch (e) { treeData.value = [] }
  finally { loading.value = false }
}

async function loadLeaves() {
  try { leafNodes.value = await intentTreeApi.getLeafNodes() || [] }
  catch (e) { leafNodes.value = [] }
}

onMounted(() => {
  loadTree()
  loadLeaves()
})
</script>

<style scoped>
.intent-tree {
  padding: 8px 0;
}
.intent-tree__node {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 2px 0;
}
.intent-tree__name {
  font-size: 14px;
  color: var(--text-regular);
}
.leaf-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  color: var(--text-regular);
  font-size: 13px;
  border-bottom: 1px solid var(--border-light);
}
.leaf-item:last-child { border-bottom: none; }
</style>
