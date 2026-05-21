<template>
  <div class="view-flex">
    <div class="view__header">
      <h2 class="view__title">用户管理</h2>
      <p class="view__subtitle">管理系统用户及其权限</p>
    </div>

    <el-card shadow="never" class="card-filter">
      <el-form :inline="true" :model="filters" size="default">
        <el-form-item label="用户名">
          <el-input v-model="filters.name" placeholder="搜索用户名" clearable style="width: 180px" />
        </el-form-item>
        <el-form-item label="等级">
          <el-select v-model="filters.userRank" clearable placeholder="全部" style="width: 120px">
            <el-option :value="0" label="0" />
            <el-option :value="1" label="1" />
            <el-option :value="2" label="2" />
            <el-option :value="3" label="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="用户组">
          <el-select v-model="filters.groupId" clearable placeholder="全部" style="width: 160px">
            <el-option v-for="g in groups" :key="g.groupId" :label="g.groupName" :value="g.groupId" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="card-table">
      <el-table
        :data="data"
        stripe
        v-loading="loading"
        @sort-change="handleSortChange"
        :max-height="tableHeight"
      >
        <el-table-column prop="id" label="ID" width="80" sortable="custom" />
        <el-table-column prop="name" label="用户名" min-width="140" sortable="custom" />
        <el-table-column prop="nickName" label="昵称" min-width="140" />
        <el-table-column prop="userRank" label="等级" width="80" sortable="custom" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="rankTag(row.userRank)">Lv.{{ row.userRank }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="row.status ? 'success' : 'info'" size="small">
              {{ row.status ? '在线' : '离线' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="editRank(row)">改等级</el-button>
            <el-button
              size="small" text
              :type="row.status ? 'warning' : 'success'"
              @click="toggleStatus(row)"
            >
              {{ row.status ? '禁用' : '启用' }}
            </el-button>
            <el-popconfirm title="确认删除该用户？" @confirm="handleDelete(row.id)">
              <template #reference>
                <el-button size="small" text type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
      <div class="table-footer">
        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </el-card>

    <!-- Rank Dialog -->
    <el-dialog v-model="rankDialog.visible" title="修改等级" width="360px">
      <el-form label-width="80px">
        <el-form-item label="用户">
          <span>{{ rankDialog.user?.name }}</span>
        </el-form-item>
        <el-form-item label="等级">
          <el-select v-model="rankDialog.rank" style="width: 120px">
            <el-option :value="0" label="0" />
            <el-option :value="1" label="1" />
            <el-option :value="2" label="2" />
            <el-option :value="3" label="3" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rankDialog.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmRank">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as userApi from '../../api/user'
import { usePagination } from '../../composables/usePagination'
import { useTableHeight } from '../../composables/useLayout'

const {
  page, size, total, data, loading,
  load, handlePageChange, handleSizeChange, handleSortChange
} = usePagination(userApi.getUsersPaged, 'id', 'asc')

const filters = reactive({ name: '', userRank: '', groupId: '' })
const groups = ref([])
const { tableHeight } = useTableHeight(80)

const rankDialog = reactive({ visible: false, user: null, rank: 0 })

function rankTag(rank) {
  const map = { 0: 'info', 1: 'primary', 2: 'success', 3: 'warning' }
  return map[rank] || 'info'
}

function handleSearch() { load({ ...filters }) }
function handleReset() {
  filters.name = ''
  filters.userRank = ''
  filters.groupId = ''
  load()
}

function editRank(row) {
  rankDialog.user = row
  rankDialog.rank = row.userRank
  rankDialog.visible = true
}

async function confirmRank() {
  try {
    await userApi.updateUserRank(rankDialog.user.id, rankDialog.rank)
    ElMessage.success('等级已更新')
    rankDialog.visible = false
    load()
  } catch (e) { /* handled */ }
}

async function toggleStatus(row) {
  try {
    await userApi.updateUserStatus(row.id, !row.status)
    ElMessage.success(row.status ? '用户已禁用' : '用户已启用')
    load()
  } catch (e) { /* handled */ }
}

async function handleDelete(id) {
  try {
    await userApi.deleteUser(id)
    ElMessage.success('用户已删除')
    load()
  } catch (e) { /* handled */ }
}

onMounted(async () => {
  load()
  try { groups.value = await userApi.getAllGroups() } catch (e) { /* ignore */ }
})
</script>

<style scoped>
.mb-4 { margin-bottom: 16px; }
.table-footer { margin-top: 16px; display: flex; justify-content: flex-end; }
</style>
