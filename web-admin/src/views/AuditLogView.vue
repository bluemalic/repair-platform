<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { pageAuditLogs } from '@/api/audit'
import type { AuditLogVO } from '@/types'

/**
 * 操作日志：**账号与基础数据的写操作**（`docs/01` §4.4）。
 *
 * 这里查不到工单流转——那有自己的时间线（工单详情里）；也查不到读操作——那是访问日志。
 * 页面上的动作下拉用的是后端给的动作码，中文名由后端拼好（新动作上线不用等前端发版）。
 */
const loading = ref(false)
const rows = ref<AuditLogVO[]>([])
const total = ref(0)
const query = reactive({
  pageNum: 1,
  pageSize: 20,
  action: undefined as string | undefined,
  operatorKeyword: '',
  /** 日期区间是数组，传给接口时拆成 startDate / endDate（含当天） */
  range: [] as string[],
})

/** 与后端 AuditAction 的码一一对应；只列出常用的，其余靠"全部"看。 */
const ACTIONS = [
  { code: 'WORKER_CREATE', label: '新增维修工' },
  { code: 'WORKER_UPDATE', label: '修改维修工' },
  { code: 'WORKER_BUILDINGS', label: '设置负责楼栋' },
  { code: 'STUDENT_CREATE', label: '新增学生' },
  { code: 'STUDENT_UPDATE', label: '修改学生' },
  { code: 'STUDENT_IMPORT', label: '批量导入学生' },
  { code: 'PASSWORD_RESET', label: '重置口令' },
  { code: 'PASSWORD_CHANGE_SELF', label: '本人修改口令' },
  { code: 'BUILDING_CREATE', label: '新增楼栋' },
  { code: 'BUILDING_UPDATE', label: '修改楼栋' },
  { code: 'BUILDING_DELETE', label: '删除楼栋' },
  { code: 'CATEGORY_CREATE', label: '新增类别' },
  { code: 'CATEGORY_UPDATE', label: '修改类别' },
  { code: 'CATEGORY_DELETE', label: '删除类别' },
  { code: 'REPAIR_CODE_CREATE', label: '新增报修码' },
  { code: 'REPAIR_CODE_UPDATE', label: '修改报修码' },
]

/** 目标类型的中文名（与后端 AuditTarget 对应）。 */
const TARGETS: Record<string, string> = {
  WORKER: '维修工',
  STUDENT: '学生',
  BUILDING: '楼栋',
  CATEGORY: '报修类别',
  REPAIR_CODE: '报修码',
  TENANT: '学校',
  ACCOUNT: '账号',
}

async function load() {
  loading.value = true
  try {
    const page = await pageAuditLogs({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      action: query.action,
      operatorKeyword: query.operatorKeyword || undefined,
      startDate: query.range?.[0] || undefined,
      endDate: query.range?.[1] || undefined,
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

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-input
        v-model="query.operatorKeyword"
        placeholder="操作人姓名"
        clearable
        style="width: 160px"
        @keyup.enter="search"
        @clear="search"
      />
      <el-select v-model="query.action" placeholder="全部动作" clearable style="width: 170px" @change="search">
        <el-option v-for="a in ACTIONS" :key="a.code" :label="a.label" :value="a.code" />
      </el-select>
      <el-date-picker
        v-model="query.range"
        type="daterange"
        value-format="YYYY-MM-DD"
        start-placeholder="开始日期"
        end-placeholder="结束日期"
        style="width: 260px"
        @change="search"
      />
      <el-button @click="search">查询</el-button>
      <span class="muted">共 {{ total }} 条</span>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="createTime" label="时间" width="170" />
      <el-table-column prop="operatorName" label="操作人" width="120" />
      <el-table-column label="动作" width="140">
        <template #default="{ row }">{{ row.actionLabel }}</template>
      </el-table-column>
      <el-table-column label="对象" width="200">
        <template #default="{ row }">
          <span class="muted">{{ TARGETS[row.targetType] ?? row.targetType }}</span>
          <span v-if="row.targetName"> · {{ row.targetName }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="detail" label="做了什么" min-width="320" />
      <el-table-column prop="ip" label="来源 IP" width="130">
        <template #default="{ row }">{{ row.ip ?? '—' }}</template>
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
  </div>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.pager {
  margin-top: 12px;
  justify-content: flex-end;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
