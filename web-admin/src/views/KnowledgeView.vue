<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { listCategories } from '@/api/category'
import {
  createKnowledge,
  pageKnowledge,
  updateKnowledge,
  updateKnowledgeStatus,
  type KnowledgeBody,
} from '@/api/knowledge'
import type { CategoryVO, KnowledgeEntryVO } from '@/types'

/**
 * 维修知识库（阶段 2 · ADR-018）。
 *
 * 页面上两处提示不是装饰，直接决定师傅端建议的质量：
 * · **现象**要写"用户会说的那种话"——它是检索主字段，写"给水管道附件渗漏"是查不出来的
 * · **关键词**补同义词——关键词检索召回弱，这一栏是留给人补的
 */
const loading = ref(false)
const rows = ref<KnowledgeEntryVO[]>([])
const total = ref(0)
const categories = ref<CategoryVO[]>([])

const query = reactive<{ pageNum: number; pageSize: number; keyword: string; categoryId: string | undefined; status: number | undefined }>({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  categoryId: undefined,
  status: undefined,
})

async function load() {
  loading.value = true
  try {
    const page = await pageKnowledge({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      keyword: query.keyword || undefined,
      categoryId: query.categoryId || undefined,
      status: query.status,
    })
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

function resetFilters() {
  query.keyword = ''
  query.categoryId = undefined
  query.status = undefined
  return search()
}

const formVisible = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<string | null>(null)
/** 类别留空 = 通用条目，所以表单里用空串表示"通用"（el-select 的 clearable 会给 undefined） */
const form = reactive<{ title: string; symptom: string; solution: string; categoryId: string; tags: string; status: number }>({
  title: '',
  symptom: '',
  solution: '',
  categoryId: '',
  tags: '',
  status: 1,
})

const rules: FormRules = {
  title: [{ required: true, message: '请填写标题', trigger: 'blur' }],
  symptom: [{ required: true, message: '请填写现象（它是检索主字段）', trigger: 'blur' }],
  solution: [{ required: true, message: '请填写处理步骤', trigger: 'blur' }],
}

function openCreate() {
  editingId.value = null
  Object.assign(form, { title: '', symptom: '', solution: '', categoryId: '', tags: '', status: 1 })
  formVisible.value = true
}

function openEdit(row: KnowledgeEntryVO) {
  editingId.value = row.id
  Object.assign(form, {
    title: row.title,
    symptom: row.symptom,
    solution: row.solution,
    categoryId: row.categoryId ?? '',
    tags: row.tags ?? '',
    status: row.status,
  })
  formVisible.value = true
}

async function submitForm() {
  if (!formRef.value) return
  await formRef.value.validate()
  const body: KnowledgeBody = {
    title: form.title,
    symptom: form.symptom,
    solution: form.solution,
    categoryId: form.categoryId || null,
    tags: form.tags || null,
    status: form.status,
  }
  if (editingId.value) {
    await updateKnowledge(editingId.value, body)
    ElMessage.success('已保存')
  } else {
    await createKnowledge(body)
    ElMessage.success('已新增条目')
  }
  formVisible.value = false
  await load()
}

/**
 * 没有删除：条目会被**历史建议**引用，删掉就断了"当时凭什么给这条建议"的线索。
 * 过期内容用停用下线即可（停用后不进检索结果）。
 */
async function toggleStatus(row: KnowledgeEntryVO) {
  const next = row.status === 1 ? 0 : 1
  if (next === 0) {
    await ElMessageBox.confirm(
      `确认停用「${row.title}」？\n\n停用后不再进入师傅端的检索结果，历史建议不受影响（所以这里只停用、不删除）。`,
      '提示',
      { type: 'warning' },
    )
  }
  await updateKnowledgeStatus(row.id, next)
  ElMessage.success(next === 1 ? '已启用' : '已停用')
  await load()
}

/** 步骤是按行渲染的，列表里只给个行数概览，详情在编辑弹窗里看 */
function stepCount(solution: string) {
  return solution.split('\n').filter((line) => line.trim()).length
}

onMounted(async () => {
  categories.value = await listCategories()
  await load()
})
</script>

<template>
  <div>
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="搜索标题或现象" clearable style="width: 220px" @keyup.enter="search" />
      <el-select v-model="query.categoryId" placeholder="全部类别" clearable style="width: 150px">
        <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
      </el-select>
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 120px">
        <el-option label="启用" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="resetFilters">重置</el-button>
      <span class="spacer" />
      <el-button @click="load">刷新</el-button>
      <el-button type="primary" @click="openCreate">新增条目</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="title" label="标题" min-width="150" />
      <el-table-column prop="symptom" label="现象（检索主字段）" min-width="240" show-overflow-tooltip />
      <el-table-column label="类别" width="110">
        <template #default="{ row }">
          {{ row.categoryName ?? '通用' }}
        </template>
      </el-table-column>
      <el-table-column label="步骤" width="80">
        <template #default="{ row }">{{ stepCount(row.solution) }} 步</template>
      </el-table-column>
      <el-table-column prop="tags" label="关键词（同义词）" min-width="150" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="updateTime" label="更新时间" width="170" />
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggleStatus(row)">
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

    <el-dialog v-model="formVisible" :title="editingId ? '编辑条目' : '新增条目'" width="600px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" placeholder="如 水管接头漏水" maxlength="64" show-word-limit />
        </el-form-item>
        <el-form-item label="现象" prop="symptom">
          <el-input
            v-model="form.symptom"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
            placeholder="写用户会说的话，如「卫生间水管接头一直滴水，地上已经积了一片」"
          />
          <div class="hint">检索主字段：师傅端的建议就是拿工单描述来匹配这一栏，写得太书面反而匹配不上</div>
        </el-form-item>
        <el-form-item label="处理步骤" prop="solution">
          <el-input v-model="form.solution" type="textarea" :rows="7" placeholder="一行一步，例如：&#10;先关掉这一路的角阀&#10;清干净旧生料带，重缠 15 圈再拧回去" />
          <div class="hint">一行一步（前端按行渲染）；写"怎么做"，不要写"可能是什么"——拿不准的判断交给师傅现场看</div>
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="form.categoryId" placeholder="通用（任何类别都可能命中）" clearable style="width: 100%">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
          <div class="hint">留空 = 通用条目。有类别的工单会优先命中同类别与通用条目</div>
        </el-form-item>
        <el-form-item label="关键词">
          <el-input v-model="form.tags" maxlength="128" placeholder="空格分隔，如：漏水 渗水 滴水 积水" />
          <div class="hint">**补同义词**：关键词检索的弱点是"渗水"命中不了"漏水"，这一栏就是留给人补的</div>
        </el-form-item>
        <el-form-item v-if="editingId" label="状态">
          <el-radio-group v-model="form.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用（不进检索结果）</el-radio>
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
  align-items: center;
}
.spacer {
  flex: 1;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
</style>
