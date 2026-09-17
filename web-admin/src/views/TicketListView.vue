<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { closeTicket, dispatchTicket, getTicketDetail, pageTickets, rejectTicket } from '@/api/ticket'
import { pageWorkers } from '@/api/worker'
import type { TicketDetailVO, TicketVO, WorkerVO } from '@/types'

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

/** 流转动作的中文名：与后端 TicketAction 一一对应（docs/02）。 */
const ACTION: Record<string, string> = {
  SUBMIT: '提交报修',
  DISPATCH: '派单',
  ACCEPT: '接单',
  ARRIVE: '到场打卡',
  FINISH: '完工上报',
  EVALUATE: '验收评价',
  CANCEL: '撤销工单',
  CLOSE: '关闭工单',
  REJECT: '驳回',
  AUTO_CLOSE: '超时自动关闭',
  ACCEPT_TIMEOUT: '接单超时提醒',
  PROCESS_TIMEOUT: '处理超时升级',
}

function statusLabel(status: number | null): string {
  if (status === null || status === undefined) {
    return '—'
  }
  return STATUS[status]?.label ?? String(status)
}

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

// ==================== 工单详情 ====================

const route = useRoute()
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<TicketDetailVO | null>(null)

async function openDetailById(ticketId: string) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await getTicketDetail(ticketId)
  } finally {
    detailLoading.value = false
  }
}

onMounted(async () => {
  await load()
  // 从通知页点"查看工单"会带 ?ticketId=xxx 过来：直接把人要看的单据打开，
  // 而不是让他自己在一页里翻（通知说的就是"哪一张单发生了什么"）
  const fromQuery = route.query.ticketId
  if (typeof fromQuery === 'string' && fromQuery) {
    await openDetailById(fromQuery)
  }
})
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
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link @click="openDetailById(row.id)">详情</el-button>
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

    <el-drawer v-model="detailVisible" :title="detail ? `工单 ${detail.ticketNo}` : '工单详情'" size="640px">
      <el-skeleton v-if="detailLoading" :rows="10" animated />
      <template v-else-if="detail">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="状态">
            <el-tag :type="STATUS[detail.status]?.type ?? 'info'">
              {{ STATUS[detail.status]?.label ?? detail.status }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="紧急度">{{ URGENCY[detail.urgency] ?? '未知' }}</el-descriptions-item>
          <el-descriptions-item label="位置">{{ detail.buildingName }} {{ detail.room }}</el-descriptions-item>
          <el-descriptions-item label="类别">{{ detail.categoryName }}</el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ detail.submitTime }}</el-descriptions-item>
          <el-descriptions-item label="派单时间">{{ detail.dispatchTime ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="到场时间">{{ detail.arriveTime ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="完工时间">{{ detail.finishTime ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="响应时长">
            {{ detail.arriveMinutes === null ? '—' : detail.arriveMinutes + ' 分钟' }}
          </el-descriptions-item>
          <el-descriptions-item label="处理时长">
            {{ detail.handleMinutes === null ? '—' : detail.handleMinutes + ' 分钟' }}
          </el-descriptions-item>
        </el-descriptions>

        <h4>问题描述</h4>
        <p class="detail-text">{{ detail.description || '（未填写）' }}</p>
        <div v-if="detail.images?.length" class="images">
          <el-image
            v-for="(url, i) in detail.images"
            :key="i"
            :src="url"
            :preview-src-list="detail.images ?? []"
            :initial-index="i"
            fit="cover"
            class="thumb"
          />
        </div>

        <template v-if="detail.resultDesc || detail.resultImages?.length">
          <h4>维修结果</h4>
          <p class="detail-text">{{ detail.resultDesc || '（未填写）' }}</p>
          <div v-if="detail.resultImages?.length" class="images">
            <el-image
              v-for="(url, i) in detail.resultImages"
              :key="i"
              :src="url"
              :preview-src-list="detail.resultImages ?? []"
              :initial-index="i"
              fit="cover"
              class="thumb"
            />
          </div>
        </template>

        <template v-if="detail.rejectReason">
          <h4>驳回原因</h4>
          <p class="detail-text">{{ detail.rejectReason }}</p>
        </template>

        <template v-if="detail.evaluation">
          <h4>验收评价</h4>
          <el-rate :model-value="detail.evaluation.score" disabled show-score />
          <p class="detail-text">{{ detail.evaluation.content || '（未填写）' }}</p>
        </template>

        <h4>流转时间线</h4>
        <el-timeline>
          <el-timeline-item
            v-for="log in detail.logs"
            :key="log.id"
            :timestamp="log.createTime"
            placement="top"
          >
            <div class="log-line">
              <strong>{{ ACTION[log.action] ?? log.action }}</strong>
              <span v-if="log.operatorName" class="muted">{{ log.operatorName }}</span>
              <el-tag v-if="log.fromStatus !== log.toStatus" size="small" type="info">
                {{ statusLabel(log.fromStatus) }} → {{ statusLabel(log.toStatus) }}
              </el-tag>
            </div>
            <div v-if="log.remark" class="muted">{{ log.remark }}</div>
          </el-timeline-item>
        </el-timeline>
      </template>
    </el-drawer>

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
.detail-text {
  margin: 4px 0 16px;
  white-space: pre-wrap;
  line-height: 1.6;
}
.images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}
.thumb {
  width: 96px;
  height: 96px;
  border-radius: 4px;
}
.log-line {
  display: flex;
  align-items: center;
  gap: 8px;
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
h4 {
  margin: 20px 0 4px;
}
.hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
</style>
