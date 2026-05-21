<template>
  <div>
    <div style="display: flex; gap: 12px; align-items: center; margin-bottom: 16px;">
      <el-button type="primary" @click="showAdd">
        <el-icon><Plus /></el-icon> 新增配置
      </el-button>
      <el-select v-model="activeGroup" placeholder="选择分组" @change="loadGroup" style="width: 200px">
        <el-option v-for="g in groups" :key="g" :label="g" :value="g" />
      </el-select>
    </div>

    <div v-loading="loading">
      <div v-if="!currentGroupData || currentGroupData.length === 0" style="text-align: center; padding: 40px; color: var(--text-secondary);">
        暂无配置
      </div>
      <el-table v-else :data="currentGroupData" stripe>
        <el-table-column prop="configKey" label="Key" min-width="200" />
        <el-table-column prop="configValue" label="Value" min-width="250" show-overflow-tooltip />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="showEdit(row)">编辑</el-button>
            <el-popconfirm title="确认删除？" @confirm="handleDelete(row.configKey)">
              <template #reference>
                <el-button size="small" text type="danger">删除</el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- Add/Edit Dialog -->
    <el-dialog v-model="form.visible" :title="form.isEdit ? '编辑配置' : '新增配置'" width="520px">
      <el-form :model="form" label-width="80px" size="default">
        <el-form-item label="分组">
          <el-input v-model="form.group" :disabled="form.isEdit" />
        </el-form-item>
        <el-form-item label="Key">
          <el-input v-model="form.configKey" :disabled="form.isEdit" />
        </el-form-item>
        <el-form-item label="Value">
          <el-input v-model="form.configValue" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="form.visible = false">取消</el-button>
        <el-button type="primary" @click="confirmForm" :loading="form.submitting">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import * as configApi from '../../api/systemConfig'

const allConfigs = ref({})
const groups = ref([])
const activeGroup = ref('')
const currentGroupData = ref([])
const loading = ref(false)
const form = reactive({
  visible: false, isEdit: false, submitting: false,
  group: '', configKey: '', configValue: '', description: ''
})

async function loadAll() {
  loading.value = true
  try {
    const data = await configApi.getAllConfigs()
    allConfigs.value = data || {}
    groups.value = Object.keys(data || {})
    if (groups.value.length > 0 && !activeGroup.value) {
      activeGroup.value = groups.value[0]
    }
    loadGroup()
  } catch (e) { /* ignore */ }
  finally { loading.value = false }
}

function loadGroup() {
  currentGroupData.value = allConfigs.value[activeGroup.value] || []
}

function showAdd() {
  form.isEdit = false
  form.group = activeGroup.value
  form.configKey = ''
  form.configValue = ''
  form.description = ''
  form.visible = true
}

function showEdit(row) {
  form.isEdit = true
  Object.assign(form, {
    group: activeGroup.value,
    configKey: row.configKey || row.key,
    configValue: row.configValue || row.value,
    description: row.description || ''
  })
  form.visible = true
}

async function confirmForm() {
  if (!form.group || !form.configKey) { ElMessage.warning('请填写分组和 Key'); return }
  form.submitting = true
  try {
    await configApi.setConfig(form.group, form.configKey, form.configValue, form.description)
    ElMessage.success(form.isEdit ? '配置已更新' : '配置已添加')
    form.visible = false
    loadAll()
  } catch (e) { /* handled */ }
  finally { form.submitting = false }
}

async function handleDelete(key) {
  try {
    await configApi.deleteConfig(activeGroup.value, key)
    ElMessage.success('配置已删除')
    loadAll()
  } catch (e) { /* handled */ }
}

onMounted(loadAll)
</script>
