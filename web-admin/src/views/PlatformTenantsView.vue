<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  addTenantAdmin,
  changeTenantStatus,
  createTenant,
  listTenantAdmins,
  pageTenants,
  resetTenantAdminPassword,
  updateTenant,
} from '@/api/platform'
import type { TenantAdminVO, TenantVO } from '@/types'

/**
 * 平台运营端 · 租户管理（docs/03 §5.5）。
 *
 * 这是平台运营**唯一**的页面，也是这个页面只有两件事的原因：开通 / 停用学校，维护学校的管理员。
 * 平台看不到任何学校的业务数据（工单、学生、统计）——那条边界由服务端的平台域拦截器强制，
 * 不是"这里没写那些接口"（ADR-012）。
 */

const loading = ref(false)
const rows = ref<TenantVO[]>([])
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
    const page = await pageTenants({ ...query })
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

// ==================== 开通学校 ====================

const createVisible = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive({
  name: '',
  code: '',
  contact: '',
  phone: '',
  adminUsername: '',
  adminRealName: '',
  adminPhone: '',
  adminPassword: '',
})

const createRules: FormRules = {
  name: [{ required: true, message: '请填写学校名称', trigger: 'blur' }],
  code: [
    { required: true, message: '请填写学校编码', trigger: 'blur' },
    {
      // 与服务端 @Pattern 一致。编码是登录参数的一部分，所以不放行大写 / 空格 / 中文：
      // 前端先拦一道是为了让人当场改，而不是提交完才看到 10001
      pattern: /^[a-z0-9][a-z0-9-]{1,31}$/,
      message: '2-32 位小写字母、数字或连字符，且不能以连字符开头',
      trigger: 'blur',
    },
  ],
  adminUsername: [{ required: true, message: '请填写管理员登录名', trigger: 'blur' }],
  adminPassword: [
    { required: true, message: '请填写管理员初始口令', trigger: 'blur' },
    { min: 8, max: 32, message: '口令长度需为 8-32 位', trigger: 'blur' },
  ],
}

function openCreate() {
  Object.assign(createForm, {
    name: '',
    code: '',
    contact: '',
    phone: '',
    adminUsername: '',
    adminRealName: '',
    adminPhone: '',
    adminPassword: '',
  })
  createVisible.value = true
}

async function submitCreate() {
  if (!(await createFormRef.value?.validate().catch(() => false))) {
    return
  }
  await createTenant({
    name: createForm.name,
    code: createForm.code,
    contact: createForm.contact || undefined,
    phone: createForm.phone || undefined,
    adminUsername: createForm.adminUsername,
    adminRealName: createForm.adminRealName || undefined,
    adminPhone: createForm.adminPhone || undefined,
    adminPassword: createForm.adminPassword,
  })
  createVisible.value = false
  ElMessage.success('已开通。请把学校编码与管理员初始口令交给学校——首次登录必须改密')
  await load()
}

// ==================== 改资料 / 启停 ====================

const editVisible = ref(false)
const editFormRef = ref<FormInstance>()
const editId = ref<string | null>(null)
const editForm = reactive({ name: '', contact: '', phone: '' })
const editRules: FormRules = {
  name: [{ required: true, message: '请填写学校名称', trigger: 'blur' }],
}

function openEdit(row: TenantVO) {
  editId.value = row.id
  Object.assign(editForm, { name: row.name, contact: row.contact ?? '', phone: row.phone ?? '' })
  editVisible.value = true
}

async function submitEdit() {
  if (!editId.value || !(await editFormRef.value?.validate().catch(() => false))) {
    return
  }
  await updateTenant(editId.value, {
    name: editForm.name,
    contact: editForm.contact || undefined,
    phone: editForm.phone || undefined,
  })
  editVisible.value = false
  ElMessage.success('已保存')
  await load()
}

/** 停用会踢掉该校全部在线用户（后端行为），文案里要说清楚，否则"停用后他们还能用"会变成说不清的 bug。 */
async function toggleStatus(row: TenantVO) {
  const next = row.status === 1 ? 0 : 1
  const tip =
    next === 0
      ? `停用后 ${row.name} 的师生会被立即踢下线，并且无法再登录（已产生的工单数据都保留），确认停用？`
      : `确认启用 ${row.name}？`
  await ElMessageBox.confirm(tip, '提示', { type: 'warning' })
  await changeTenantStatus(row.id, next)
  ElMessage.success(next === 1 ? '已启用' : '已停用')
  await load()
}

// ==================== 管理员 ====================

const adminsVisible = ref(false)
const adminsLoading = ref(false)
const adminsTarget = ref<TenantVO | null>(null)
const admins = ref<TenantAdminVO[]>([])

async function openAdmins(row: TenantVO) {
  adminsTarget.value = row
  adminsVisible.value = true
  await loadAdmins()
}

async function loadAdmins() {
  if (!adminsTarget.value) return
  adminsLoading.value = true
  try {
    admins.value = await listTenantAdmins(adminsTarget.value.id)
  } finally {
    adminsLoading.value = false
  }
}

const adminVisible = ref(false)
const adminFormRef = ref<FormInstance>()
const adminForm = reactive({ username: '', realName: '', phone: '', password: '' })
const adminRules: FormRules = {
  username: [{ required: true, message: '请填写登录名', trigger: 'blur' }],
  password: [
    { required: true, message: '请填写初始口令', trigger: 'blur' },
    { min: 8, max: 32, message: '口令长度需为 8-32 位', trigger: 'blur' },
  ],
}

function openAddAdmin() {
  Object.assign(adminForm, { username: '', realName: '', phone: '', password: '' })
  adminVisible.value = true
}

async function submitAddAdmin() {
  if (!adminsTarget.value || !(await adminFormRef.value?.validate().catch(() => false))) {
    return
  }
  await addTenantAdmin(adminsTarget.value.id, {
    username: adminForm.username,
    realName: adminForm.realName || undefined,
    phone: adminForm.phone || undefined,
    password: adminForm.password,
  })
  adminVisible.value = false
  ElMessage.success('已新增管理员，请把初始口令交给他——首次登录必须改密')
  await loadAdmins()
}

async function resetPassword(admin: TenantAdminVO) {
  const { value } = await ElMessageBox.prompt(
    `给 ${admin.realName || admin.username} 设一个新口令。这是后勤管理员忘记口令时唯一的出路（本项目不做短信 / 邮件找回）。`,
    '重置口令',
    {
      inputType: 'password',
      inputPlaceholder: '8-32 位',
      inputValidator: (v: string) => (v && v.length >= 8 && v.length <= 32) || '口令长度需为 8-32 位',
    },
  )
  await resetTenantAdminPassword(adminsTarget.value!.id, admin.id, value)
  ElMessage.success('已重置，他下次登录必须改密')
  await loadAdmins()
}

onMounted(load)
</script>

<template>
  <div>
    <p class="scope-tip">
      这里只管学校与学校的管理员。平台运营**看不到任何学校的业务数据**（工单 / 学生 / 统计）——
      那条边界在服务端的拦截器里，不靠这里少写几个接口。
    </p>

    <div class="toolbar">
      <el-input
        v-model="query.keyword"
        placeholder="学校名称或编码"
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
      <el-button type="primary" @click="openCreate">开通学校</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="name" label="学校名称" min-width="160" />
      <el-table-column prop="code" label="编码" width="120" />
      <el-table-column prop="contact" label="联系人" width="100">
        <template #default="{ row }">{{ row.contact ?? '—' }}</template>
      </el-table-column>
      <el-table-column prop="phone" label="联系电话" width="130">
        <template #default="{ row }">{{ row.phone ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="开通时间" width="160" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openAdmins(row)">管理员</el-button>
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
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

    <el-dialog v-model="createVisible" title="开通学校" width="560px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="110px">
        <el-form-item label="学校名称" prop="name">
          <el-input v-model="createForm.name" placeholder="如：广东海洋大学" />
        </el-form-item>
        <el-form-item label="学校编码" prop="code">
          <el-input v-model="createForm.code" placeholder="师生登录时填，如 gdou；建好后不能改" />
        </el-form-item>
        <el-form-item label="联系人" prop="contact">
          <el-input v-model="createForm.contact" placeholder="可选，如：后勤管理处" />
        </el-form-item>
        <el-form-item label="联系电话" prop="phone">
          <el-input v-model="createForm.phone" placeholder="可选" />
        </el-form-item>
        <el-divider content-position="left">第一个后勤管理员</el-divider>
        <el-form-item label="登录名" prop="adminUsername">
          <el-input v-model="createForm.adminUsername" placeholder="如 admin" />
        </el-form-item>
        <el-form-item label="姓名" prop="adminRealName">
          <el-input v-model="createForm.adminRealName" placeholder="可选" />
        </el-form-item>
        <el-form-item label="手机号" prop="adminPhone">
          <el-input v-model="createForm.adminPhone" placeholder="可选" />
        </el-form-item>
        <el-form-item label="初始口令" prop="adminPassword">
          <el-input
            v-model="createForm.adminPassword"
            type="password"
            show-password
            placeholder="8-32 位；这是一次性凭证，他首次登录必须改"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">开通</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="修改学校资料" width="480px">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="90px">
        <el-form-item label="学校名称" prop="name">
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="联系人" prop="contact">
          <el-input v-model="editForm.contact" />
        </el-form-item>
        <el-form-item label="联系电话" prop="phone">
          <el-input v-model="editForm.phone" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="adminsVisible" :title="`${adminsTarget?.name ?? ''} · 后勤管理员`" width="700px">
      <div class="dialog-toolbar">
        <el-button type="primary" @click="openAddAdmin">新增管理员</el-button>
      </div>
      <el-table v-loading="adminsLoading" :data="admins" border>
        <el-table-column prop="username" label="登录名" width="120" />
        <el-table-column prop="realName" label="姓名" width="100">
          <template #default="{ row }">{{ row.realName ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="phone" label="手机号" width="120">
          <template #default="{ row }">{{ row.phone ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="口令" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.mustChangePassword" type="warning">待改密</el-tag>
            <span v-else class="muted">已激活</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="resetPassword(row)">重置口令</el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="adminsVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="adminVisible" title="新增管理员" width="480px" append-to-body>
      <el-form ref="adminFormRef" :model="adminForm" :rules="adminRules" label-width="90px">
        <el-form-item label="登录名" prop="username">
          <el-input v-model="adminForm.username" placeholder="该学校内唯一" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="adminForm.realName" placeholder="可选" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="adminForm.phone" placeholder="可选" />
        </el-form-item>
        <el-form-item label="初始口令" prop="password">
          <el-input
            v-model="adminForm.password"
            type="password"
            show-password
            placeholder="8-32 位；他首次登录必须改"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="adminVisible = false">取消</el-button>
        <el-button type="primary" @click="submitAddAdmin">保存</el-button>
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
.dialog-toolbar {
  margin-bottom: 12px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.muted {
  color: var(--el-text-color-secondary);
}
.scope-tip {
  margin: 0 0 12px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
