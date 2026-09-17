<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { createRepairCode, pageRepairCodes, updateRepairCode } from '@/api/repairCode'
import { listBuildings } from '@/api/building'
import type { BuildingVO, RepairCodeDetailVO } from '@/types'

const loading = ref(false)
const rows = ref<RepairCodeDetailVO[]>([])
const total = ref(0)
const buildings = ref<BuildingVO[]>([])
const query = reactive({
  pageNum: 1,
  pageSize: 10,
  buildingId: undefined as string | undefined,
  status: undefined as number | undefined,
})

async function load() {
  loading.value = true
  try {
    const page = await pageRepairCodes({ ...query })
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

// ==================== 生成 ====================

const createVisible = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive({ buildingId: undefined as string | undefined, room: '' })
const createRules: FormRules = {
  buildingId: [{ required: true, message: '请选择楼栋', trigger: 'change' }],
  room: [{ required: true, message: '请填写房间号', trigger: 'blur' }],
}

function openCreate() {
  Object.assign(createForm, { buildingId: undefined, room: '' })
  createVisible.value = true
}

async function submitCreate() {
  if (!createFormRef.value) return
  await createFormRef.value.validate()
  const created = await createRepairCode(createForm.buildingId as string, createForm.room)
  // 码是随机生成的，所以必须把结果亮出来——管理员要把它抄到贴纸上
  ElMessageBox.alert(
    `房间 ${created.buildingName} ${created.room} 的报修码是：${created.code}\n\n二维码内容就是这个 6 位数字，前端渲染打印后贴在房间门口。`,
    '生成成功',
    { confirmButtonText: '知道了' },
  )
  createVisible.value = false
  await search()
}

// ==================== 修改（房间 / 启停 / 重新生成） ====================

const editVisible = ref(false)
const editFormRef = ref<FormInstance>()
const editing = ref<RepairCodeDetailVO | null>(null)
const editForm = reactive({ room: '', status: 1, regenerate: false })
const editRules: FormRules = {
  room: [{ required: true, message: '请填写房间号', trigger: 'blur' }],
}

function openEdit(row: RepairCodeDetailVO) {
  editing.value = row
  Object.assign(editForm, { room: row.room, status: row.status, regenerate: false })
  editVisible.value = true
}

async function submitEdit() {
  if (!editFormRef.value || !editing.value) return
  await editFormRef.value.validate()
  if (editForm.regenerate) {
    await ElMessageBox.confirm(
      '重新生成会换一个新码，**旧码立即失效**（已经贴在门上的要换掉）。确认继续？',
      '提示',
      { type: 'warning' },
    )
  }
  await updateRepairCode(editing.value.id, {
    room: editForm.room,
    status: editForm.status,
    regenerate: editForm.regenerate || undefined,
  })
  ElMessage.success(editForm.regenerate ? '已重新生成，旧码已失效' : '已保存')
  editVisible.value = false
  await load()
}

/** 码要抄去打印，给个一键复制（浏览器非 https 下 clipboard 不可用，所以留好兜底提示）。 */
async function copyCode(code: string) {
  try {
    await navigator.clipboard.writeText(code)
    ElMessage.success(`已复制 ${code}`)
  } catch {
    ElMessage.info(`复制不可用，请手动记下：${code}`)
  }
}

onMounted(async () => {
  await Promise.all([load(), listBuildings().then((list) => (buildings.value = list))])
})
</script>

<template>
  <div>
    <div class="toolbar">
      <el-select
        v-model="query.buildingId"
        placeholder="全部楼栋"
        clearable
        filterable
        style="width: 180px"
        @change="search"
      >
        <el-option v-for="b in buildings" :key="b.id" :label="b.name" :value="b.id" />
      </el-select>
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 140px" @change="search">
        <el-option label="启用" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>
      <el-button @click="search">查询</el-button>
      <el-button type="primary" @click="openCreate">生成报修码</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column label="报修码" width="140">
        <template #default="{ row }">
          <el-button link type="primary" @click="copyCode(row.code)">{{ row.code }}</el-button>
        </template>
      </el-table-column>
      <el-table-column label="位置" min-width="180">
        <template #default="{ row }">{{ row.buildingName }} {{ row.room }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="生成时间" width="180" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
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

    <el-dialog v-model="createVisible" title="生成报修码" width="440px">
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="80px">
        <el-form-item label="楼栋" prop="buildingId">
          <el-select v-model="createForm.buildingId" filterable placeholder="选择楼栋" style="width: 100%">
            <el-option v-for="b in buildings" :key="b.id" :label="b.name" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="房间号" prop="room">
          <el-input v-model="createForm.room" placeholder="如 3-412" />
        </el-form-item>
      </el-form>
      <p class="dialog-tip">
        码由服务端随机生成（6 位数字，不接受指定），生成后请在结果里抄下来。
        同一房间只能有一张码——要换码请编辑原记录并勾选「重新生成」。
      </p>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">生成</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑报修码" width="460px">
      <el-form ref="editFormRef" :model="editForm" :rules="editRules" label-width="100px">
        <el-form-item label="当前码">
          <span>{{ editing?.code }}（{{ editing?.buildingName }} {{ editing?.room }}）</span>
        </el-form-item>
        <el-form-item label="房间号" prop="room">
          <el-input v-model="editForm.room" />
        </el-form-item>
        <el-form-item label="状态">
          <el-radio-group v-model="editForm.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用（扫码返回"码无效"）</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="重新生成">
          <el-checkbox v-model="editForm.regenerate">换一个新码（旧码立即失效）</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="submitEdit">保存</el-button>
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
.dialog-tip {
  margin: 0;
  font-size: 12px;
  line-height: 1.6;
  color: var(--el-text-color-secondary);
}
</style>
