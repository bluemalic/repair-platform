<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createStudent,
  importStudents,
  pageStudents,
  resetStudentPassword,
  updateStudent,
} from '@/api/student'
import type { StudentImportResult, StudentVO } from '@/types'

const loading = ref(false)
const rows = ref<StudentVO[]>([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10, status: undefined as number | undefined, keyword: '' })

async function load() {
  loading.value = true
  try {
    const page = await pageStudents({ ...query })
    rows.value = page.list
    total.value = page.total
  } catch {
    // http 里已经弹过错误提示
  } finally {
    loading.value = false
  }
}

function search() {
  query.pageNum = 1
  load()
}

onMounted(load)

// ---------- 新增 / 编辑 ----------

const formVisible = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<string | null>(null)
const form = reactive({ username: '', realName: '', phone: '', password: '' })

const rules: FormRules = {
  username: [
    { required: true, message: '请输入学号', trigger: 'blur' },
    { max: 32, message: '学号最长 32 位', trigger: 'blur' },
  ],
  realName: [{ max: 32, message: '姓名最长 32 位', trigger: 'blur' }],
  phone: [{ max: 20, message: '手机号最长 20 位', trigger: 'blur' }],
  password: [
    {
      // 新增必填；编辑留空表示不改。这条规则与后端一致（@Size 对 null 不校验）
      validator: (_rule, value: string, callback) => {
        if (!editingId.value && !value) {
          callback(new Error('请输入初始口令'))
        } else if (value && (value.length < 8 || value.length > 32)) {
          callback(new Error('口令长度需在 8-32 位之间'))
        } else {
          callback()
        }
      },
      trigger: 'blur',
    },
  ],
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { username: '', realName: '', phone: '', password: '' })
  formVisible.value = true
}

function openEdit(row: StudentVO) {
  editingId.value = row.id
  Object.assign(form, {
    username: row.username,
    realName: row.realName ?? '',
    phone: row.phone ?? '',
    password: '',
  })
  formVisible.value = true
}

async function submitForm() {
  if (!(await formRef.value?.validate().catch(() => false))) {
    return
  }
  if (editingId.value) {
    await updateStudent(editingId.value, {
      realName: form.realName || undefined,
      phone: form.phone || undefined,
      status: rows.value.find((r) => r.id === editingId.value)?.status ?? 1,
      // 留空即不改：不能传空字符串，后端会把它当成"要设成空口令"
      password: form.password || undefined,
    })
    ElMessage.success('已保存')
  } else {
    await createStudent({
      username: form.username,
      realName: form.realName || undefined,
      phone: form.phone || undefined,
      password: form.password,
    })
    ElMessage.success('已新增。该学生首次登录会被要求修改初始口令')
  }
  formVisible.value = false
  await load()
}

// ---------- 启停 / 重置口令 ----------

async function toggleStatus(row: StudentVO) {
  const next = row.status === 1 ? 0 : 1
  const tip = next === 1
    ? `确认启用「${row.realName || row.username}」？`
    : `确认停用「${row.realName || row.username}」？停用后他当前的登录态会立即失效。`
  await ElMessageBox.confirm(tip, '提示', { type: 'warning' })
  await updateStudent(row.id, { realName: row.realName ?? undefined, phone: row.phone ?? undefined, status: next })
  ElMessage.success(next === 1 ? '已启用' : '已停用')
  await load()
}

async function resetPassword(row: StudentVO) {
  const { value } = await ElMessageBox.prompt(
    `给「${row.realName || row.username}」设一个新口令（8-32 位）。他下次登录会被要求再改一次。`,
    '重置口令',
    { inputType: 'password', inputPlaceholder: '新口令', inputValidator: (v) => (v && v.length >= 8 && v.length <= 32) || '口令需为 8-32 位' },
  )
  await resetStudentPassword(row.id, value)
  ElMessage.success('已重置')
  await load()
}

// ---------- 批量导入 ----------

const importVisible = ref(false)
const importing = ref(false)
const importForm = reactive({ rows: '', password: '' })
const importResult = ref<StudentImportResult | null>(null)

function openImport() {
  importForm.rows = ''
  importForm.password = ''
  importResult.value = null
  importVisible.value = true
}

async function submitImport() {
  if (!importForm.rows.trim()) {
    ElMessage.warning('请粘贴学号名单')
    return
  }
  if (importForm.password.length < 8 || importForm.password.length > 32) {
    ElMessage.warning('初始口令需为 8-32 位')
    return
  }
  importing.value = true
  try {
    importResult.value = await importStudents(importForm.rows, importForm.password)
    await load()
  } catch {
    // http 已提示（例如超过 500 条会上报服务端的明确文案）
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <div>
    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="学号或姓名"
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
      <el-button type="primary" @click="openCreate">新增学生</el-button>
      <el-button @click="openImport">批量导入</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="username" label="学号" width="160" />
      <el-table-column prop="realName" label="姓名" width="140">
        <template #default="{ row }">{{ row.realName ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="phone" label="手机号" width="160">
        <template #default="{ row }">{{ row.phone ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="口令状态" min-width="160">
        <template #default="{ row }">
          <el-tag v-if="row.mustChangePassword" type="warning" size="small">待改密（初始口令）</el-tag>
          <span v-else class="muted">已改过</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="primary" @click="resetPassword(row)">重置口令</el-button>
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

    <el-dialog v-model="formVisible" :title="editingId ? '编辑学生' : '新增学生'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="学号" prop="username">
          <el-input v-model="form.username" :disabled="!!editingId" placeholder="学号即登录名，租户内唯一" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="可选" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="可选" />
        </el-form-item>
        <el-form-item :label="editingId ? '重置口令' : '初始口令'" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="8-32 位，留空表示不修改" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="批量导入学生" width="560px">
      <el-alert type="info" :closable="false" show-icon class="import-tip">
        <template #title>
          每行一个学号，也可以写「学号,姓名」。已存在的学号会被自动跳过——补录名单里带着上次已导的人是常事。
        </template>
      </el-alert>
      <el-form :model="importForm" label-width="90px">
        <el-form-item label="学号名单">
          <el-input
            v-model="importForm.rows"
            type="textarea"
            :rows="8"
            placeholder="20260003,张三&#10;20260004,李四&#10;20260005"
          />
        </el-form-item>
        <el-form-item label="初始口令">
          <el-input v-model="importForm.password" type="password" show-password placeholder="8-32 位；这批学生首次登录都要改掉它" />
        </el-form-item>
      </el-form>

      <el-alert v-if="importResult" :type="importResult.created > 0 ? 'success' : 'warning'" :closable="false" show-icon>
        <template #title>
          新建 {{ importResult.created }} 个，跳过 {{ importResult.skipped }} 个，忽略 {{ importResult.ignored }} 行
        </template>
        <template v-if="importResult.skippedUsernames.length" #default>
          <div class="skipped">
            跳过的学号：{{ importResult.skippedUsernames.join('、') }}
          </div>
        </template>
      </el-alert>

      <template #footer>
        <el-button @click="importVisible = false">关闭</el-button>
        <el-button type="primary" :loading="importing" @click="submitImport">开始导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}
.pager {
  margin-top: 16px;
}
.muted {
  color: var(--el-text-color-secondary);
}
.import-tip {
  margin-bottom: 16px;
}
.skipped {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.6;
  word-break: break-all;
}
</style>
