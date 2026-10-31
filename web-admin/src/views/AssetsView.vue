<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { createAsset, deleteAsset, pageAssets, updateAsset } from '@/api/asset'
import { listBuildings } from '@/api/building'
import { listCategories } from '@/api/category'
import type { AssetVO, BuildingVO, CategoryVO } from '@/types'

const loading = ref(false)
const rows = ref<AssetVO[]>([])
const total = ref(0)
const buildings = ref<BuildingVO[]>([])
const categories = ref<CategoryVO[]>([])

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  buildingId: undefined as string | undefined,
  categoryId: undefined as string | undefined,
  status: undefined as number | undefined,
  keyword: '',
})

async function load() {
  loading.value = true
  try {
    const page = await pageAssets({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      buildingId: query.buildingId,
      categoryId: query.categoryId,
      status: query.status,
      keyword: query.keyword.trim() || undefined,
    })
    rows.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

function resetQuery() {
  Object.assign(query, {
    pageNum: 1,
    buildingId: undefined,
    categoryId: undefined,
    status: undefined,
    keyword: '',
  })
  void load()
}

const formVisible = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<string | null>(null)
const form = reactive({
  assetNo: '',
  name: '',
  buildingId: undefined as string | undefined,
  categoryId: undefined as string | undefined,
  room: '',
  status: 1,
  purchaseDate: '',
  warrantyUntil: '',
  supplier: '',
  remark: '',
})

const rules: FormRules = {
  assetNo: [{ required: true, message: '请填写设备编号', trigger: 'blur' }],
  name: [{ required: true, message: '请填写设备名称', trigger: 'blur' }],
}

const statusText = (status: number) => ({ 1: '在用', 2: '维修中', 3: '停用/报废' })[status] ?? '未知'
const statusTag = (status: number) =>
  (({ 1: 'success', 2: 'warning', 3: 'info' })[status] ?? 'info') as 'success' | 'warning' | 'info'

function openCreate() {
  editingId.value = null
  Object.assign(form, {
    assetNo: '',
    name: '',
    buildingId: undefined,
    categoryId: undefined,
    room: '',
    status: 1,
    purchaseDate: '',
    warrantyUntil: '',
    supplier: '',
    remark: '',
  })
  formVisible.value = true
}

function openEdit(row: AssetVO) {
  editingId.value = row.id
  Object.assign(form, {
    assetNo: row.assetNo,
    name: row.name,
    buildingId: row.buildingId ?? undefined,
    categoryId: row.categoryId ?? undefined,
    room: row.room ?? '',
    status: row.status,
    purchaseDate: row.purchaseDate ?? '',
    warrantyUntil: row.warrantyUntil ?? '',
    supplier: row.supplier ?? '',
    remark: row.remark ?? '',
  })
  formVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  await formRef.value.validate()
  const body = {
    assetNo: form.assetNo.trim(),
    name: form.name.trim(),
    buildingId: form.buildingId,
    categoryId: form.categoryId,
    room: form.room.trim() || undefined,
    purchaseDate: form.purchaseDate || undefined,
    warrantyUntil: form.warrantyUntil || undefined,
    supplier: form.supplier.trim() || undefined,
    remark: form.remark.trim() || undefined,
  }
  if (editingId.value) {
    await updateAsset(editingId.value, { ...body, status: form.status })
    ElMessage.success('已保存')
  } else {
    await createAsset(body)
    ElMessage.success('已新增设备')
  }
  formVisible.value = false
  await load()
}

/**
 * 删除只适用于"建错了、还没被工单用过"的设备：有过维修历史的设备后端会拒绝，
 * 因为历史工单会变成空白。下线一台在用的设备，请走「编辑 → 停用/报废」。
 */
async function doDelete(row: AssetVO) {
  await ElMessageBox.confirm(
    `确认删除「${row.assetNo} ${row.name}」？\n\n删除只适用于建错的设备：一旦有工单引用过，后端会拒绝并说明原因。\n下线一台在用的设备，请改用「编辑 → 停用/报废」。`,
    '提示',
    { type: 'warning', confirmButtonText: '确认删除', confirmButtonClass: 'el-button--danger' },
  )
  await deleteAsset(row.id)
  ElMessage.success('已删除')
  await load()
}

function onPageChange(page: number) {
  query.pageNum = page
  void load()
}

function onSizeChange(size: number) {
  query.pageSize = size
  query.pageNum = 1
  void load()
}

onMounted(async () => {
  // 字典数据量小，一次拿全：筛选与表单共用同一份，避免切换弹窗时再查
  const [buildingList, categoryList] = await Promise.all([listBuildings(), listCategories()])
  buildings.value = buildingList
  categories.value = categoryList
  await load()
})
</script>

<template>
  <div>
    <div class="toolbar">
      <el-select v-model="query.buildingId" clearable placeholder="全部楼栋" style="width: 150px">
        <el-option v-for="b in buildings" :key="b.id" :label="b.name" :value="b.id" />
      </el-select>
      <el-select v-model="query.categoryId" clearable placeholder="全部类别" style="width: 150px">
        <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 130px">
        <el-option label="在用" :value="1" />
        <el-option label="维修中" :value="2" />
        <el-option label="停用/报废" :value="3" />
      </el-select>
      <el-input
        v-model="query.keyword"
        clearable
        placeholder="编号或名称"
        style="width: 180px"
        @keyup.enter="query.pageNum = 1; load()"
      />
      <el-button type="primary" @click="query.pageNum = 1; load()">查询</el-button>
      <el-button @click="resetQuery">重置</el-button>
      <el-button type="primary" plain @click="openCreate">新增设备</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="assetNo" label="设备编号" min-width="150" />
      <el-table-column prop="name" label="设备名称" min-width="150" />
      <el-table-column label="位置" min-width="180">
        <template #default="{ row }">
          {{ row.buildingName ?? '—' }}<span v-if="row.room"> / {{ row.room }}</span>
        </template>
      </el-table-column>
      <el-table-column label="类别" width="110">
        <template #default="{ row }">{{ row.categoryName ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="保修至" width="120">
        <template #default="{ row }">{{ row.warrantyUntil ?? '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="doDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      background
      layout="total, sizes, prev, pager, next"
      :total="total"
      :current-page="query.pageNum"
      :page-size="query.pageSize"
      :page-sizes="[10, 20, 50]"
      @current-change="onPageChange"
      @size-change="onSizeChange"
    />

    <el-dialog v-model="formVisible" :title="editingId ? '编辑设备' : '新增设备'" width="560px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="设备编号" prop="assetNo">
          <el-input v-model="form.assetNo" placeholder="如 AC-3-201-01（租户内唯一）" maxlength="64" />
        </el-form-item>
        <el-form-item label="设备名称" prop="name">
          <el-input v-model="form.name" placeholder="如 3号楼201空调" maxlength="64" />
        </el-form-item>
        <el-form-item label="楼栋">
          <el-select v-model="form.buildingId" clearable placeholder="可选" style="width: 100%">
            <el-option v-for="b in buildings" :key="b.id" :label="b.name" :value="b.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="房间/位置">
          <el-input v-model="form.room" placeholder="如 201" maxlength="32" />
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="form.categoryId" clearable placeholder="可选" style="width: 100%">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="editingId" label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">在用</el-radio>
            <el-radio :value="2">维修中</el-radio>
            <el-radio :value="3">停用/报废</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="采购日期">
          <el-date-picker
            v-model="form.purchaseDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="可选"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="保修截止">
          <el-date-picker
            v-model="form.warrantyUntil"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="可选"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="供应商">
          <el-input v-model="form.supplier" placeholder="可选" maxlength="64" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" maxlength="255" placeholder="可选" />
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
  flex-wrap: wrap;
  justify-content: flex-end;
}

.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
