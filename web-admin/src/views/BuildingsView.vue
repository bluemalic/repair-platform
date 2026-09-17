<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { createBuilding, deleteBuilding, listBuildings, updateBuilding } from '@/api/building'
import type { BuildingVO } from '@/types'

const loading = ref(false)
const rows = ref<BuildingVO[]>([])

async function load() {
  loading.value = true
  try {
    // 不带 status：管理列表要能看到已停用的，否则停用后就再也改不回来
    rows.value = await listBuildings()
  } finally {
    loading.value = false
  }
}

const formVisible = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<string | null>(null)
const form = reactive({ name: '', area: '', sort: 0, status: 1 })

const rules: FormRules = {
  name: [{ required: true, message: '请填写楼栋名称', trigger: 'blur' }],
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', area: '', sort: 0, status: 1 })
  formVisible.value = true
}

function openEdit(row: BuildingVO) {
  editingId.value = row.id
  Object.assign(form, { name: row.name, area: row.area ?? '', sort: row.sort, status: row.status })
  formVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  await formRef.value.validate()
  const body = { name: form.name, area: form.area || undefined, sort: form.sort }
  if (editingId.value) {
    await updateBuilding(editingId.value, { ...body, status: form.status })
    ElMessage.success('已保存')
  } else {
    await createBuilding(body)
    ElMessage.success('已新增楼栋')
  }
  formVisible.value = false
  await load()
}

/**
 * 删除是逻辑删除，而且**只允许删从未被引用的楼栋**（有工单 / 有师傅负责 / 有报修码都会被后端拒绝）。
 * 所以这里把"下线用停用"说在最前面，避免管理员把删除当成下线的常规手段。
 */
async function doDelete(row: BuildingVO) {
  await ElMessageBox.confirm(
    `确认删除「${row.name}」？\n\n删除只适用于建错的楼栋：一旦它有工单、有师傅负责或挂了报修码，后端会拒绝并说明原因。\n下线一栋在用的楼，请改用「编辑 → 停用」。`,
    '提示',
    { type: 'warning', confirmButtonText: '确认删除', confirmButtonClass: 'el-button--danger' },
  )
  await deleteBuilding(row.id)
  ElMessage.success('已删除')
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="openCreate">新增楼栋</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="name" label="楼栋名称" min-width="160" />
      <el-table-column prop="area" label="所属区域" width="140">
        <template #default="{ row }">{{ row.area ?? '—' }}</template>
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

    <el-dialog v-model="formVisible" :title="editingId ? '编辑楼栋' : '新增楼栋'" width="440px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="楼栋名称" prop="name">
          <el-input v-model="form.name" placeholder="如 6号楼" />
        </el-form-item>
        <el-form-item label="所属区域" prop="area">
          <el-input v-model="form.area" placeholder="如 东区（可选）" />
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
</style>
