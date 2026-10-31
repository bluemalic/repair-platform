<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createWorkerGroup,
  deleteWorkerGroup,
  listWorkerGroups,
  listWorkerShifts,
  saveWorkerShift,
  setWorkerGroupMembers,
  updateWorkerGroup,
} from '@/api/schedule'
import { pageWorkers } from '@/api/worker'
import type { WorkerGroupVO, WorkerShiftVO, WorkerVO } from '@/types'

const activeTab = ref<'groups' | 'shifts'>('groups')

// ==================== 工种分组 ====================

const groups = ref<WorkerGroupVO[]>([])
const groupsLoading = ref(false)

async function loadGroups() {
  groupsLoading.value = true
  try {
    groups.value = await listWorkerGroups()
  } finally {
    groupsLoading.value = false
  }
}

const formVisible = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<string | null>(null)
const form = reactive({ name: '', sort: 0, status: 1 })

const rules: FormRules = {
  name: [{ required: true, message: '请填写工种名称', trigger: 'blur' }],
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { name: '', sort: 0, status: 1 })
  formVisible.value = true
}

function openEdit(row: WorkerGroupVO) {
  editingId.value = row.id
  Object.assign(form, { name: row.name, sort: row.sort, status: row.status })
  formVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  await formRef.value.validate()
  const body = { name: form.name.trim(), sort: form.sort }
  if (editingId.value) {
    await updateWorkerGroup(editingId.value, { ...body, status: form.status })
    ElMessage.success('已保存')
  } else {
    await createWorkerGroup(body)
    ElMessage.success('已新增工种分组')
  }
  formVisible.value = false
  await loadGroups()
}

async function doDelete(row: WorkerGroupVO) {
  await ElMessageBox.confirm(
    `确认删除「${row.name}」？\n\n组内还有维修工、或仍有报修类别把该工种设为默认时，后端会拒绝并说明原因。`,
    '提示',
    { type: 'warning', confirmButtonText: '确认删除', confirmButtonClass: 'el-button--danger' },
  )
  await deleteWorkerGroup(row.id)
  ElMessage.success('已删除')
  await loadGroups()
}

// 成员弹窗：一次拉全启用中的维修工（人员规模是百级，分页拉全比搜索框简单）
const membersVisible = ref(false)
const membersSaving = ref(false)
const membersGroup = ref<WorkerGroupVO | null>(null)
const memberIds = ref<string[]>([])
const workers = ref<WorkerVO[]>([])

async function openMembers(row: WorkerGroupVO) {
  membersGroup.value = row
  memberIds.value = [...row.workerIds]
  membersVisible.value = true
  // 每次打开都刷新一遍：刚建的维修工要能立刻加进来
  await loadAllWorkers()
}

async function saveMembers() {
  if (!membersGroup.value) return
  membersSaving.value = true
  try {
    await setWorkerGroupMembers(membersGroup.value.id, memberIds.value)
    ElMessage.success('成员已保存')
    membersVisible.value = false
    await loadGroups()
  } finally {
    membersSaving.value = false
  }
}

/** 分页拉全启用中的维修工（pageSize 上限 100，超过时要继续翻页）。 */
async function loadAllWorkers() {
  const all: WorkerVO[] = []
  for (let page = 1; page <= 50; page++) {
    const result = await pageWorkers({ pageNum: page, pageSize: 100, status: 1 })
    all.push(...result.list)
    if (all.length >= result.total || !result.list.length) {
      break
    }
  }
  workers.value = all
}

// ==================== 排班 ====================

function todayString(): string {
  const now = new Date()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${now.getFullYear()}-${month}-${day}`
}

const shiftDate = ref(todayString())
const shiftType = ref(1)
const shifts = ref<WorkerShiftVO[]>([])
const shiftsLoading = ref(false)

interface ShiftRow {
  worker: WorkerVO
  status: number | null
}

const shiftRows = computed<ShiftRow[]>(() => {
  const byWorker = new Map<number, WorkerShiftVO>()
  shifts.value
    .filter((shift) => shift.shiftType === shiftType.value)
    .forEach((shift) => byWorker.set(Number(shift.workerId), shift))
  return workers.value.map((worker) => ({
    worker,
    status: byWorker.get(Number(worker.id))?.status ?? null,
  }))
})

async function loadShifts() {
  if (!shiftDate.value) return
  shiftsLoading.value = true
  try {
    shifts.value = await listWorkerShifts(shiftDate.value)
  } finally {
    shiftsLoading.value = false
  }
}

async function changeStatus(row: ShiftRow, status: number) {
  await saveWorkerShift({
    workerId: row.worker.id,
    shiftDate: shiftDate.value,
    shiftType: shiftType.value,
    status,
  })
  ElMessage.success(`已设置 ${row.worker.realName} 为${status === 1 ? '在班' : '请假/休息'}`)
  await loadShifts()
}

onMounted(async () => {
  await Promise.all([loadGroups(), loadAllWorkers()])
  await loadShifts()
})
</script>

<template>
  <div>
    <el-tabs v-model="activeTab">
      <el-tab-pane label="工种分组" name="groups">
        <div class="toolbar">
          <el-button @click="loadGroups">刷新</el-button>
          <el-button type="primary" @click="openCreate">新增工种分组</el-button>
        </div>

        <el-table v-loading="groupsLoading" :data="groups" border>
          <el-table-column prop="name" label="工种名称" min-width="180" />
          <el-table-column prop="sort" label="排序" width="90" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="组内人数" width="110">
            <template #default="{ row }">{{ row.workerCount }}</template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openMembers(row)">成员</el-button>
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="doDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <div class="hint">工种只参与派单评分的技能维度，不影响师傅能看到哪些单——负责楼栋才决定可见范围。</div>
      </el-tab-pane>

      <el-tab-pane label="排班" name="shifts">
        <div class="toolbar">
          <el-date-picker v-model="shiftDate" type="date" value-format="YYYY-MM-DD" placeholder="选择日期" style="width: 160px" />
          <el-select v-model="shiftType" style="width: 120px">
            <el-option label="白班" :value="1" />
            <el-option label="夜班" :value="2" />
          </el-select>
          <el-button @click="loadShifts">刷新</el-button>
        </div>

        <el-table v-loading="shiftsLoading" :data="shiftRows" border>
          <el-table-column label="维修工" min-width="180">
            <template #default="{ row }">{{ row.worker.realName }}（{{ row.worker.username }}）</template>
          </el-table-column>
          <el-table-column label="值班状态" width="220">
            <template #default="{ row }">
              <el-select
                :model-value="row.status"
                placeholder="未排班"
                style="width: 180px"
                @update:model-value="(value: number) => changeStatus(row, value)"
              >
                <el-option label="在班" :value="1" />
                <el-option label="请假/休息" :value="0" />
              </el-select>
            </template>
          </el-table-column>
        </el-table>

        <div class="hint">
          「未排班」与「请假/休息」不是一回事：没排班记录在派单评分里记中性分，明确请假才会排到最后。
          派单仍然只排序、由调度员点确认。
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="formVisible" :title="editingId ? '编辑工种分组' : '新增工种分组'" width="420px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="工种名称" prop="name">
          <el-input v-model="form.name" placeholder="如 水电工 / 空调工" maxlength="32" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort" :min="0" />
        </el-form-item>
        <el-form-item v-if="editingId" label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="membersVisible"
      :title="membersGroup ? `组内成员 · ${membersGroup.name}` : '组内成员'"
      width="520px"
    >
      <el-select v-model="memberIds" multiple filterable placeholder="选择维修工" style="width: 100%">
        <el-option
          v-for="worker in workers"
          :key="worker.id"
          :label="`${worker.realName}（${worker.username}）`"
          :value="worker.id"
        />
      </el-select>
      <div class="hint">只列启用中的维修工；清空后保存即移出全部成员。</div>
      <template #footer>
        <el-button @click="membersVisible = false">取消</el-button>
        <el-button type="primary" :loading="membersSaving" @click="saveMembers">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.hint {
  margin-top: 12px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
