<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { closeTicket, dispatchTicket, pageTickets, rejectTicket } from '@/api/ticket'
import { pageWorkers } from '@/api/worker'
import type { TicketVO, WorkerVO } from '@/types'

/** 状态字典：与后端 TicketStatus 一一对应（docs/02）。 */
const STATUS: Record<number, { label: string; type: 'info' | 'warning' | 'primary' | 'success' | 'danger' }> = {
  10: { label: '待派单', type: 'warning' },
  20: { label: '待接单', type: 'primary' },
  30: { label: '处理中', type: 'primary' },
  40: { label: '待验收', type: 'info' },
  50: { label: '已完成', type: 'success' },
  60: { label: '已关闭', type: 'info' },
  70: { label: '已撤单', type: 'info' },
  80: { label: '已驳回', type: 'danger' },
}

const URGENCY: Record<number, string> = { 1: '普通', 2: '紧急', 3: '特急' }

const loading = ref(false)
const rows = ref<TicketVO[]>([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 10, status: undefined as number | undefined })

async function load() {
  loading.value = true
  try {
    const page = await pageTickets({ ...query })
    rows.value = page.list
    total.value = page.total
  } finally {
    loading.value = false
  }
}

// ==================== 派单选人 ====================

const dispatchVisible = ref(false)
const dispatchTarget = ref<TicketVO | null>(null)
const selectedWorkerId = ref<string | undefined>(undefined)
const workerOptions = ref<WorkerVO[]>([])
const workerSearching = ref(false)
/** 命中总数：下拉里只放前 N 条，超过时提示"用关键字继续搜"，避免让人以为就这么多 */
const workerTotal = ref(0)

async function openDispatch(row: TicketVO) {
  dispatchTarget.value = row
  selectedWorkerId.value = undefined
  dispatchVisible.value = true
  await searchWorkers('')
}

/**
 * 维修工下拉走服务端搜索（`pageSize` 有上限，一次拉全在人多时会静默截断）：
 * 打开时先给一页可选项，输入关键字再查。
 */
async function searchWorkers(keyword: string) {
  workerSearching.value = true
  try {
    const page = await pageWorkers({
      pageNum: 1,
      pageSize: 20,
      status: 1,
      keyword: keyword || undefined,
    })
    workerOptions.value = page.list
    workerTotal.value = page.total
  } finally {
    workerSearching.value = false
  }
}

/** 下拉里显示"姓名（工号）— 负责 1号楼、2号楼"：派单要看的就是"他管不管这栋楼"。 */
function workerLabel(worker: WorkerVO): string {
  const buildings = worker.buildingNames.length ? worker.buildingNames.join('、') : '未配置楼栋'
  return `${worker.realName}（${worker.username}）— 负责 ${buildings}`
}

async function confirmDispatch() {
  if (!dispatchTarget.value || !selectedWorkerId.value) return
  await dispatchTicket(dispatchTarget.value.id, selectedWorkerId.value)
  ElMessage.success('派单成功')
  dispatchVisible.value = false
  await load()
}

async function doReject(row: TicketVO) {
  const { value } = await ElMessageBox.prompt('驳回原因', `驳回 · ${row.ticketNo}`, {
    inputValidator: (v) => (v ? true : '请填写原因'),
  })
  await rejectTicket(row.id, value)
  ElMessage.success('已驳回')
  await load()
}

async function doClose(row: TicketVO) {
  await ElMessageBox.confirm(`确认关闭工单 ${row.ticketNo}？`, '提示', { type: 'warning' })
  await closeTicket(row.id)
  ElMessage.success('已关闭')
  await load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <el-select v-model="query.status" placeholder="全部状态" clearable style="width: 160px" @change="load">
        <el-option v-for="(v, k) in STATUS" :key="k" :label="v.label" :value="Number(k)" />
      </el-select>
      <el-button @click="load">刷新</el-button>
    </div>

    <el-table v-loading="loading" :data="rows" border>
      <el-table-column prop="ticketNo" label="工单号" width="170" />
      <el-table-column label="位置" min-width="150">
        <template #default="{ row }">{{ row.buildingName }} {{ row.room }}</template>
      </el-table-column>
      <el-table-column prop="categoryName" label="类别" width="90" />
      <el-table-column label="紧急度" width="90">
        <template #default="{ row }">{{ URGENCY[row.urgency] ?? '未知' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="STATUS[row.status]?.type ?? 'info'">{{ STATUS[row.status]?.label ?? row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="submitTime" label="提交时间" width="170" />
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 10 || row.status === 80" link type="primary" @click="openDispatch(row)">
            派单
          </el-button>
          <el-button v-if="[20, 30, 40].includes(row.status)" link type="danger" @click="doReject(row)">驳回</el-button>
          <el-button v-if="row.status === 50" link type="primary" @click="doClose(row)">关闭</el-button>
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

    <el-dialog v-model="dispatchVisible" title="派单" width="520px">
      <el-form label-width="72px">
        <el-form-item label="工单">
          <span>
            {{ dispatchTarget?.ticketNo }} · {{ dispatchTarget?.buildingName }} {{ dispatchTarget?.room }}
          </span>
        </el-form-item>
        <el-form-item label="维修工">
          <el-select
            v-model="selectedWorkerId"
            filterable
            remote
            reserve-keyword
            :remote-method="searchWorkers"
            :loading="workerSearching"
            placeholder="输入工号或姓名搜索"
            style="width: 100%"
          >
            <el-option v-for="w in workerOptions" :key="w.id" :label="workerLabel(w)" :value="w.id" />
          </el-select>
          <div class="hint">
            只列出启用中的维修工，共 {{ workerTotal }} 位；看不全就输入关键字继续搜
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dispatchVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!selectedWorkerId" @click="confirmDispatch">确认派单</el-button>
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
.hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
</style>
