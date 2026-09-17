<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { createWorker, pageWorkers, setWorkerBuildings, updateWorker } from '@/api/worker'
import { listBuildings } from '@/api/building'
import type { BuildingVO, WorkerVO } from '@/types'

const loading = ref(false)
const rows = ref<WorkerVO[]>([])
const total = ref(0)
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  status: undefined as number | undefined,
  keyword: '',
})

async function load() {
  loading.value = true
  try {
    const page = await pageWorkers({ ...query })
    rows.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function search() {
  query.pageNum = 1
  return load()
}

// ==================== 楼栋选项 ====================

/**
 * **不过滤停用状态**：设置负责楼栋是"全量替换"，如果下拉里没有某个已停用的楼栋而这位师傅正负责它，
 * 编辑一次就会把它悄悄抹掉。所以全量列出，停用的加个后缀标明。
 */
const buildings = ref<BuildingVO[]>([])

async function loadBuildings() {
  buildings.value = await listBuildings()
}

function buildingLabel(building: BuildingVO): string {
  const area = building.area ? `${building.area} · ` : ''
  return `${area}${building.name}${building.status === 1 ? '' : '（已停用）'}`
}

// ==================== 新增 / 编辑 ====================

const formVisible = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<string | null>(null)
const form = reactive({ username: '', realName: '', phone: '', password: '', status: 1 })

const rules: FormRules = {
  username: [{ required: true, message: '请填写工号（登录名）', trigger: 'blur' }],
  realName: [{ required: true, message: '请填写姓名', trigger: 'blur' }],
  password: [
    {
      // 新增必填；编辑时留空表示不改密码
      validator: (_rule, value: string, callback) => {
        if (!value) {
          return editingId.value ? callback() : callback(new Error('请填写初始密码'))
        }
        if (value.length < 8 || value.length > 32) {
          return callback(new Error('密码长度需在 8-32 位之间'))
        }
        return callback()
      },
      trigger: 'blur',
    },
  ],
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { username: '', realName: '', phone: '', password: '', status: 1 })
  formVisible.value = true
}

function openEdit(row: WorkerVO) {
  editingId.value = row.id
  Object.assign(form, {
    username: row.username,
    realName: row.realName,
    phone: row.phone ?? '',
    password: '',
    status: row.status,
  })
  formVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  await formRef.value.validate()
  if (editingId.value) {
    await updateWorker(editingId.value, {
      realName: form.realName,
      phone: form.phone || undefined,
      status: form.status,
      password: form.password || undefined,
    })
    ElMessage.success('已保存')
  } else {
    await createWorker({
      username: form.username,
      realName: form.realName,
      phone: form.phone || undefined,
      password: form.password,
    })
    ElMessage.success('已新增维修工')
  }
  formVisible.value = false
  await load()
}

/**
 * 快捷启停。停用会连带踢下线（后端行为），所以文案里要说清楚——
 * 否则"停用后他还能接单"会变成一个说不清的 bug。
 */
async function toggleStatus(row: WorkerVO) {
  const next = row.status === 1 ? 0 : 1
  const tip =
    next === 0
      ? `停用后 ${row.realName} 已登录的 token 会立即失效（无法再接单、上报），确认停用？`
      : `确认启用 ${row.realName}？`
  await ElMessageBox.confirm(tip, '提示', { type: 'warning' })
  await updateWorker(row.id, { realName: row.realName, phone: row.phone ?? undefined, status: next })
  ElMessage.success(next === 1 ? '已启用' : '已停用')
  await load()
}

// ==================== 设置负责楼栋 ====================

const scopeVisible = ref(false)
const scopeTarget = ref<WorkerVO | null>(null)
const scopeBuildingIds = ref<string[]>([])

function openScope(row: WorkerVO) {
  scopeTarget.value = row
  scopeBuildingIds.value = [...row.buildingIds]
  scopeVisible.value = true
}

async function submitScope() {
  if (!scopeTarget.value) return
  await setWorkerBuildings(scopeTarget.value.id, scopeBuildingIds.value)
  ElMessage.success(scopeBuildingIds.value.length ? '已保存负责楼栋' : '已清空负责楼栋（他将看不到任何工单）')
  scopeVisible.value = false
  await load()
}

onMounted(async () => {
  await Promise.all([load(), loadBuildings()])
})
</script>

<template>
  <div>
    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="工号或姓名"
        clearable
        style="width: 200px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="search">
        <el-option label="启用" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>
      <el-button @click="search">查询</el-button>
      <el-button type="primary" @click="openCreate">新增维修工</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="username" label="工号" width="140" />
      <el-table-column prop="realName" label="姓名" width="120" />
      <el-table-column prop="phone" label="手机号" width="140">
        <template #default="{ row }">{{ row.phone ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="负责楼栋" min-width="200">
        <template #default="{ row }">
          <span v-if="row.buildingNames.length">{{ row.buildingNames.join('、') }}</span>
          <span v-else class="muted">未配置（他看不到任何工单）</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="openScope(row)">负责楼栋</el-button>
          <el-button link :type="row.status === 1 ? 'danger' : 'success'" @click="toggleStatus(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :page-size="query.pageSize"
      :current-page="query.pageNum"
      @current-change="(p: number) => { query.pageNum = p; load() }"
    />

    <el-dialog v-model="formVisible" :title="editingId ? '编辑维修工' : '新增维修工'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="工号" prop="username">
          <el-input v-model="form.username" :disabled="!!editingId" placeholder="工号即登录名，租户内唯一" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="可选" />
        </el-form-item>
        <el-form-item :label="editingId ? '重置密码' : '初始密码'" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            :placeholder="editingId ? '留空表示不修改' : '8-32 位，请当面告知师傅'"
          />
        </el-form-item>
        <el-form-item v-if="editingId" label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用（会踢下线）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="scopeVisible" title="设置负责楼栋" width="520px">
      <p class="dialog-tip">
        这是 {{ scopeTarget?.realName }} 数据可见范围的依据：他只能看到所负责楼栋的工单。
        <strong>全量替换</strong>——没勾选的等于解除负责关系；一个都不勾，他将看不到任何工单。
      </p>
      <el-select v-model="scopeBuildingIds" multiple filterable placeholder="选择楼栋" style="width: 100%">
        <el-option v-for="b in buildings" :key="b.id" :label="buildingLabel(b)" :value="b.id" />
      </el-select>
      <template #footer>
        <el-button @click="scopeVisible = false">取消</el-button>
        <el-button type="primary" @click="submitScope">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.muted {
  color: var(--el-text-color-secondary);
}
.dialog-tip {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
}
</style>
