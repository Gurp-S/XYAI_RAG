<template>
  <div>
    <div class="view__header">
      <h2 class="view__title">用户管理</h2>
      <p class="view__subtitle">管理系统用户和用户组</p>
    </div>
    <el-tabs v-model="activeTab" class="merged-tabs">
      <el-tab-pane label="用户列表" name="list">
        <UserList ref="userList" />
      </el-tab-pane>
      <el-tab-pane label="用户组" name="groups">
        <UserGroups />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import UserList from './UserList.vue'
import UserGroups from './UserGroups.vue'

const activeTab = ref('list')
</script>

<style scoped>
.merged-tabs { margin-top: -8px; }
</style>
