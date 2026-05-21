<template>
  <div class="view-flex">
    <div class="view__header">
      <h2 class="view__title">用户组管理</h2>
      <p class="view__subtitle">查看用户组及组成员</p>
    </div>

    <el-card shadow="never" class="card-table">
      <el-table :data="groups" stripe v-loading="loading" @row-click="showDetail" :max-height="tableHeight">
        <el-table-column prop="groupId" label="组 ID" width="100" />
        <el-table-column prop="groupName" label="组名称" min-width="200" />
        <el-table-column prop="memberCount" label="成员数" width="120" align="center" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="showDetail(row)">查看成员</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- Group Detail Dialog -->
    <el-dialog v-model="detail.visible" :title="`组详情: ${detail.group?.groupName}`" width="600px">
      <template v-if="detail.group">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="组 ID">{{ detail.group.groupId }}</el-descriptions-item>
          <el-descriptions-item label="组名称">{{ detail.group.groupName }}</el-descriptions-item>
          <el-descriptions-item label="成员数">{{ detail.memberCount }}</el-descriptions-item>
        </el-descriptions>
        <el-divider />
        <h4 style="margin: 0 0 12px; color: var(--text-primary);">成员列表</h4>
        <el-table :data="detail.members" stripe size="small" max-height="400">
          <el-table-column prop="id" label="ID" width="80" />
          <el-table-column prop="name" label="用户名" min-width="150" />
          <el-table-column prop="userRank" label="等级" width="80">
            <template #default="{ row }">
              <el-tag size="small">Lv.{{ row.userRank }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import * as userApi from '../../api/user'
import { useTableHeight } from '../../composables/useLayout'

const loading = ref(false)
const { tableHeight } = useTableHeight()
const groups = ref([])
const detail = reactive({ visible: false, group: null, members: [], memberCount: 0 })

async function loadGroups() {
  loading.value = true
  try {
    groups.value = await userApi.getAllGroups()
  } catch (e) { /* ignore */ }
  finally { loading.value = false }
}

async function showDetail(row) {
  try {
    const res = await userApi.getGroupDetail(row.groupId)
    detail.group = res.group || res
    detail.members = res.members || []
    detail.memberCount = res.memberCount || 0
    detail.visible = true
  } catch (e) { /* ignore */ }
}

onMounted(loadGroups)
</script>
