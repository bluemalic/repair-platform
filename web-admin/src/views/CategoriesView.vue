<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { createCategory, deleteCategory, listCategories, updateCategory } from '@/api/category'
import type { CategoryVO } from '@/types'

/** 与后端 ticket_category.default_urgency 对应（docs/02）。 */
const URGENCY = [
  { value: 1, label: '普通' },
  { value: 2, label: '紧急' },
  { value: 3, label: '特急' },
]

const loading = ref(false)
const rows = ref<CategoryVO[]>([])

async function load() {
  loading.value = true
  try {
    rows.value = await listCategories()
  } finally {
    loading.value = false
  }
}

const formVisible = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<string | null>(null)
const form = reactive({ name: '', defaultUrgency: 1, sort: 0, status: 1 })

const rules: FormRules = {
  name: [{ required: true, message: '请填写类别名称', trigger: 'blur' }],
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', defaultUrgency: 1, sort: 0, status: 1 })
  formVisible.value = true
}

function openEdit(row: CategoryVO) {
  editingId.value = row.id
  Object.assign(form, {
    name: row.name,
    defaultUrgency: row.defaultUrgency,
    sort: row.sort,
    status: row.status,
  })
  formVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  await formRef.value.validate()
  const body = { name: form.name, defaultUrgency: form.defaultUrgency, sort: form.sort }
  if (editingId.value) {
    await updateCategory(editingId.value, { ...body, status: form.status })
    ElMessage.success('已保存')
  } else {
    await createCategory(body)
    ElMessage.success('已新增类别')
  }
  formVisible.value = false
  await load()
}

/**
 * 删除只适用于"建错的类别"：被工单引用过的类别只能停用（停用后不能用于新报修，历史工单照常显示名称）。
 */
async function doDelete(row: CategoryVO) {
  await ElMessageBox.confirm(
    `确认删除「${row.name}」？\n\n已经被工单引用过的类别不能删除（后端会拒绝并说明原因）——那种情况请改用「编辑 → 停用」。`,
    '提示',
    { type: 'warning', confirmButtonText: '确认删除', confirmButtonClass: 'el-button--danger' },
  )
  await deleteCategory(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="openCreate">新增类别</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="name" label="类别名称" min-width="160" />
      <el-table-column label="默认紧急度" width="130">
        <template #default="{ row }">
          {{ URGENCY.find((u) => u.value === row.defaultUrgency)?.label ?? row.defaultUrgency }}
        </template>
      </el-table-column>
      <el-table-column prop="sort" label="排序" width="90" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="doDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="formVisible" :title="editingId ? '编辑类别' : '新增类别'" width="440px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="类别名称" prop="name">
          <el-input v-model="form.name" placeholder="如 门窗" />
        </el-form-item>
        <el-form-item label="默认紧急度" prop="defaultUrgency">
          <el-select v-model="form.defaultUrgency" style="width: 100%">
            <el-option v-for="u in URGENCY" :key="u.value" :label="u.label" :value="u.value" />
          </el-select>
          <div class="hint">学生没选紧急度时用这个值，减少填写负担</div>
        </el-form-item>
        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" />
        </el-form-item>
        <el-form-item v-if="editingId" label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用（不能用于新报修）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  justify-content: flex-end;
}
.hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
</style>
