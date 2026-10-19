<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  addTenantAdmin,
  changeTenantStatus,
  createTenant,
  getTenantSso,
  listTenantAdmins,
  pageTenants,
  resetTenantAdminPassword,
  updateTenant,
  updateTenantSso,
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
  // 账号配额：开通时就能定，留空用服务端默认（200 / 20）。0 = 不限
  maxStudents: 200,
  maxWorkers: 20,
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
    maxStudents: createForm.maxStudents,
    maxWorkers: createForm.maxWorkers,
  })
  createVisible.value = false
  ElMessage.success('已开通。请把学校编码与管理员初始口令交给学校——首次登录必须改密')
  await load()
}

// ==================== 改资料 / 启停 ====================

const editVisible = ref(false)
const editFormRef = ref<FormInstance>()
const editId = ref<string | null>(null)
const editForm = reactive({ name: '', contact: '', phone: '', maxStudents: 200, maxWorkers: 20 })
const editRules: FormRules = {
  name: [{ required: true, message: '请填写学校名称', trigger: 'blur' }],
}

function openEdit(row: TenantVO) {
  editId.value = row.id
  Object.assign(editForm, {
    name: row.name, contact: row.contact ?? '', phone: row.phone ?? '',
    maxStudents: row.maxStudents, maxWorkers: row.maxWorkers,
  })
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
    maxStudents: editForm.maxStudents,
    maxWorkers: editForm.maxWorkers,
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

// ==================== 统一认证（阶段 7 · ADR-022） ====================

const ssoVisible = ref(false)
const ssoLoading = ref(false)
const ssoTarget = ref<TenantVO | null>(null)
/** 是否配置过（false 时对话框里是默认值） */
const ssoConfigured = ref(false)
/** 密钥是否已配置：**只用于占位提示**，接口永远不回显密钥本身 */
const ssoSecretConfigured = ref(false)

const ssoForm = reactive({
  enabled: false,
  protocol: 'OAUTH2' as 'OAUTH2' | 'CAS',
  authorizeUrl: '',
  tokenUrl: '',
  userinfoUrl: '',
  clientId: '',
  clientSecret: '',
  scope: '',
  casLoginUrl: '',
  casValidateUrl: '',
  usernameAttribute: 'username',
  adminRedirectUrl: '',
  h5RedirectUrl: '',
})

/** 要在学校 IdP 那边登记的回调地址（当前站点推导） */
const ssoCallbackUrl = computed(() => `${location.origin}/api/auth/sso/callback`)

async function openSso(row: TenantVO) {
  ssoTarget.value = row
  ssoVisible.value = true
  ssoLoading.value = true
  try {
    const config = await getTenantSso(row.id)
    ssoConfigured.value = config.configured
    ssoSecretConfigured.value = config.clientSecretConfigured
    Object.assign(ssoForm, {
      enabled: config.enabled,
      protocol: config.protocol === 'CAS' ? 'CAS' : 'OAUTH2',
      authorizeUrl: config.authorizeUrl ?? '',
      tokenUrl: config.tokenUrl ?? '',
      userinfoUrl: config.userinfoUrl ?? '',
      clientId: config.clientId ?? '',
      // 永不回显：留空 = 不修改
      clientSecret: '',
      scope: config.scope ?? '',
      casLoginUrl: config.casLoginUrl ?? '',
      casValidateUrl: config.casValidateUrl ?? '',
      usernameAttribute: config.usernameAttribute || 'username',
      adminRedirectUrl: config.adminRedirectUrl ?? '',
      h5RedirectUrl: config.h5RedirectUrl ?? '',
    })
  } finally {
    ssoLoading.value = false
  }
}

async function submitSso() {
  if (!ssoTarget.value) return
  // 与后端同一套必填项：前端先拦一道，让人当场看到缺什么（后端仍会再校验一次）
  const missing: string[] = []
  if (ssoForm.protocol === 'OAUTH2') {
    if (!ssoForm.authorizeUrl.trim()) missing.push('授权地址')
    if (!ssoForm.tokenUrl.trim()) missing.push('换 token 地址')
    if (!ssoForm.userinfoUrl.trim()) missing.push('用户信息地址')
    if (!ssoForm.clientId.trim()) missing.push('客户端 ID')
  } else {
    if (!ssoForm.casLoginUrl.trim()) missing.push('CAS 登录地址')
    if (!ssoForm.casValidateUrl.trim()) missing.push('CAS 校验地址')
  }
  if (ssoForm.enabled && !ssoForm.adminRedirectUrl.trim() && !ssoForm.h5RedirectUrl.trim()) {
    missing.push('至少一个登录后的跳转地址')
  }
  if (missing.length) {
    ElMessage.warning('还缺：' + missing.join('、'))
    return
  }
  await updateTenantSso(ssoTarget.value.id, {
    enabled: ssoForm.enabled ? 1 : 0,
    protocol: ssoForm.protocol,
    authorizeUrl: ssoForm.authorizeUrl.trim() || undefined,
    tokenUrl: ssoForm.tokenUrl.trim() || undefined,
    userinfoUrl: ssoForm.userinfoUrl.trim() || undefined,
    clientId: ssoForm.clientId.trim() || undefined,
    // 留空 = 不修改：平台运营改别的字段时不必重新输入密钥
    clientSecret: ssoForm.clientSecret || undefined,
    scope: ssoForm.scope.trim() || undefined,
    casLoginUrl: ssoForm.casLoginUrl.trim() || undefined,
    casValidateUrl: ssoForm.casValidateUrl.trim() || undefined,
    usernameAttribute: ssoForm.usernameAttribute.trim() || undefined,
    adminRedirectUrl: ssoForm.adminRedirectUrl.trim() || undefined,
    h5RedirectUrl: ssoForm.h5RedirectUrl.trim() || undefined,
  })
  ElMessage.success(ssoForm.enabled ? '已保存，登录页会出现统一认证入口' : '已保存（统一认证已停用）')
  ssoVisible.value = false
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
      <el-table-column label="账号配额（已用 / 上限）" min-width="200">
        <template #default="{ row }">
          <div>学生 {{ row.studentCount }} / {{ row.maxStudents > 0 ? row.maxStudents : '不限' }}</div>
          <div>维修工 {{ row.workerCount }} / {{ row.maxWorkers > 0 ? row.maxWorkers : '不限' }}</div>
        </template>
      </el-table-column>
      <el-table-column label="统一认证" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.ssoEnabled" type="success">{{ row.ssoProtocol }}</el-tag>
          <span v-else class="muted">未开通</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="开通时间" width="160" />
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openAdmins(row)">管理员</el-button>
          <el-button link type="primary" @click="openSso(row)">统一认证</el-button>
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
        <el-divider content-position="left">账号配额</el-divider>
        <el-form-item label="学生账号上限">
          <el-input-number v-model="createForm.maxStudents" :min="0" />
          <span class="quota-hint">0 = 不限。到上限后学校就无法再建学生账号</span>
        </el-form-item>
        <el-form-item label="维修工上限">
          <el-input-number v-model="createForm.maxWorkers" :min="0" />
          <span class="quota-hint">0 = 不限</span>
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
        <el-form-item label="学生上限">
          <el-input-number v-model="editForm.maxStudents" :min="0" />
          <span class="quota-hint">0 = 不限</span>
        </el-form-item>
        <el-form-item label="维修工上限">
          <el-input-number v-model="editForm.maxWorkers" :min="0" />
          <span class="quota-hint">0 = 不限</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="ssoVisible" :title="`${ssoTarget?.name ?? ''} · 统一身份认证`" width="660px">
      <el-alert type="info" :closable="false" class="sso-tip">
        <template #title>
          在学校 IdP 那边登记回调地址：<b>{{ ssoCallbackUrl }}</b>
          （CAS 还要允许 service 带查询串）。平台**不会**因为认证通过就自动建号——账号要先在学校后台存在。
        </template>
      </el-alert>
      <el-form :model="ssoForm" label-width="120px" v-loading="ssoLoading">
        <el-form-item label="启用">
          <el-switch v-model="ssoForm.enabled" />
          <span class="sso-hint">关闭时登录页不显示统一认证入口</span>
        </el-form-item>
        <el-form-item label="协议">
          <el-radio-group v-model="ssoForm.protocol">
            <el-radio value="OAUTH2">OAuth2（授权码 + PKCE）</el-radio>
            <el-radio value="CAS">CAS（ticket 校验）</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="ssoForm.protocol === 'OAUTH2'">
          <el-form-item label="授权地址">
            <el-input v-model="ssoForm.authorizeUrl" placeholder="浏览器跳转，必须外网可达" />
          </el-form-item>
          <el-form-item label="换 token 地址">
            <el-input v-model="ssoForm.tokenUrl" placeholder="服务端调用（内网地址更快更稳）" />
          </el-form-item>
          <el-form-item label="用户信息地址">
            <el-input v-model="ssoForm.userinfoUrl" placeholder="服务端调用" />
          </el-form-item>
          <el-form-item label="客户端 ID">
            <el-input v-model="ssoForm.clientId" />
          </el-form-item>
          <el-form-item label="客户端密钥">
            <el-input
              v-model="ssoForm.clientSecret"
              type="password"
              show-password
              :placeholder="ssoSecretConfigured ? '已配置（留空表示不修改）' : '按学校 IdP 提供的填写'"
            />
          </el-form-item>
          <el-form-item label="scope">
            <el-input v-model="ssoForm.scope" placeholder="留空用 openid profile" />
          </el-form-item>
        </template>

        <template v-else>
          <el-form-item label="CAS 登录地址">
            <el-input v-model="ssoForm.casLoginUrl" placeholder="浏览器跳转，如 https://cas.edu.cn/cas/login" />
          </el-form-item>
          <el-form-item label="CAS 校验地址">
            <el-input v-model="ssoForm.casValidateUrl" placeholder="服务端调用，如 .../cas/serviceValidate" />
          </el-form-item>
        </template>

        <el-form-item label="登录名字段">
          <el-input v-model="ssoForm.usernameAttribute" placeholder="IdP 返回的哪个属性是学号 / 工号，默认 username" />
        </el-form-item>
        <el-form-item label="管理端跳回">
          <el-input v-model="ssoForm.adminRedirectUrl" placeholder="如 https://repair.edu.cn/" />
        </el-form-item>
        <el-form-item label="H5 跳回">
          <el-input v-model="ssoForm.h5RedirectUrl" placeholder="如 https://repair.edu.cn/h5/" />
        </el-form-item>
        <el-form-item>
          <span class="sso-hint">
            跳转地址只允许用这里配的（请求只能选 ADMIN / H5），否则这个入口会变成开放重定向。
            {{ ssoConfigured ? '' : '这所学校还没有配置过，按下发资料填写即可。' }}
          </span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="ssoVisible = false">取消</el-button>
        <el-button type="primary" @click="submitSso">保存</el-button>
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
        <el-table-column label="统一认证" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.ssoEnabled" type="success">{{ row.ssoProtocol }}</el-tag>
          <span v-else class="muted">未开通</span>
        </template>
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
.sso-tip {
  margin-bottom: 16px;
}
.sso-hint {
  margin-left: 8px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
.quota-hint {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

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
