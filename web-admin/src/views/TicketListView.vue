<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { closeTicket, dispatchTicket, getDispatchCandidates, getTicketDetail, mergeTicket, pageTickets, rejectTicket, resumeTicket, transferTicket } from '@/api/ticket'
import { addCollaborator, removeCollaborator, splitTicket } from '@/api/ticket'
import { listCategories } from '@/api/category'
import { pageWorkers } from '@/api/worker'
import type { CategoryVO, DispatchCandidateVO, TicketDetailVO, TicketVO, WorkerVO } from '@/types'

/** 状态字典：与后端 TicketStatus 一一对应（docs/02）。 */
const STATUS: Record<number, { label: string; type: 'info' | 'warning' | 'primary' | 'success' | 'danger' }> = {
  10: { label: '待派单', type: 'warning' },
  20: { label: '待接单', type: 'primary' },
  30: { label: '处理中', type: 'primary' },
  35: { label: '待料', type: 'warning' },
  40: { label: '待验收', type: 'info' },
  45: { label: '无法维修', type: 'danger' },
  50: { label: '已完成', type: 'success' },
  60: { label: '已关闭', type: 'info' },
  70: { label: '已撤单', type: 'info' },
  75: { label: '已合并', type: 'info' },
  80: { label: '已驳回', type: 'danger' },
}

const URGENCY: Record<number, string> = { 1: '普通', 2: '紧急', 3: '特急' }

/** 流转动作的中文名：与后端 TicketAction 一一对应（docs/02）。 */
const ACTION: Record<string, string> = {
  SUBMIT: '提交报修',
  DISPATCH: '派单',
  ACCEPT: '接单',
  ARRIVE: '到场打卡',
  WAIT_MATERIAL: '待料',
  UNREPAIRABLE: '无法维修',
  RESUME: '恢复处理',
  FINISH: '完工上报',
  EVALUATE: '验收评价',
  CANCEL: '撤销工单',
  CLOSE: '关闭工单',
  REJECT: '驳回',
  REWORK: '验收不通过（打回重做）',
  TRANSFER: '转派',
  ADD_COLLABORATOR: '加协作者',
  REMOVE_COLLABORATOR: '移除协作者',
  SPLIT: '拆单',
  MERGE: '合并工单',
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
/** 派单模式的候选排名（阶段 3 · ADR-019）；转派/加协作者仍走服务端搜索 */
const candidates = ref<DispatchCandidateVO[]>([])
const candidatesLoading = ref(false)
/** 是否显示今天明确请假/休息的师傅（没排班记录的始终保留，按中性分） */
const includeOffDuty = ref(false)
const workerSearching = ref(false)
/** 命中总数：下拉里只放前 N 条，超过时提示"用关键字继续搜"，避免让人以为就这么多 */
const workerTotal = ref(0)
/**
 * 同一个弹窗给三种动作用：`dispatch`（10/80 → 20，还没有师傅）、`transfer`（20/30 → 20，换人）、
 * `collaborator`（20/30，把另一个师傅拉进来一起干，`docs/01` §4.5）。
 * 合并的理由是**三边的表单、选人逻辑完全一样**，差别只在"要不要写理由""要不要跨楼栋确认"和调哪个接口；
 * 拆成三个弹窗会让这段选人代码抄三遍。
 */
const dispatchMode = ref<'dispatch' | 'transfer' | 'collaborator'>('dispatch')
const transferReason = ref('')

function openDispatch(row: TicketVO) {
  dispatchMode.value = 'dispatch'
  openWorkerPicker(row)
}

function openTransfer(row: TicketVO) {
  dispatchMode.value = 'transfer'
  transferReason.value = ''
  openWorkerPicker(row)
}

function openCollaborator(row: TicketVO) {
  dispatchMode.value = 'collaborator'
  openWorkerPicker(row)
}

const dialogTitle = computed(() => {
  if (dispatchMode.value === 'transfer') return '转派'
  if (dispatchMode.value === 'collaborator') return '加协作者'
  return '派单'
})

async function openWorkerPicker(row: TicketVO) {
  dispatchTarget.value = row
  selectedWorkerId.value = undefined
  candidates.value = []
  includeOffDuty.value = false
  dispatchVisible.value = true
  if (dispatchMode.value === 'dispatch') {
    await loadCandidates(row.id)
  } else {
    await searchWorkers('')
  }
}

/**
 * 派单候选：后端按「技能 / 满意度 / 负载 / 距离 / 值班」五项加权排序，每项都带得分与理由。
 * 候选就是这所学校全部启用中的师傅，排序已经把该看的排前面，所以这里不做搜索框。
 */
async function loadCandidates(ticketId: string) {
  candidatesLoading.value = true
  try {
    candidates.value = await getDispatchCandidates(ticketId, includeOffDuty.value)
  } finally {
    candidatesLoading.value = false
  }
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

/**
 * 派单 / 转派的共同提交口。
 *
 * <p>选中的师傅不负责这栋楼时**必须先确认一次**——跨楼栋是"紧急抽调"，不是常规操作
 * （`docs/01` §4.2）：服务端放行并留痕，但要让调度员意识到自己越过了楼栋约束。
 * 选中的人负责这栋楼时直接提交，不多一步点击。
 */
async function confirmDispatch() {
  const target = dispatchTarget.value
  const workerId = selectedWorkerId.value
  if (!target || !workerId) return
  const transferring = dispatchMode.value === 'transfer'
  const collaborating = dispatchMode.value === 'collaborator'
  if (transferring && !transferReason.value.trim()) {
    ElMessage.warning('请填写转派理由：原师傅要知道为什么这单被转走了')
    return
  }

  const candidate = candidates.value.find((item) => item.workerId === workerId)
  const worker = workerOptions.value.find((item) => item.id === workerId)
  const covered = candidate
    ? candidate.responsibleForBuilding
    : !worker || worker.buildingIds.includes(target.buildingId)
  // 加协作者不做跨楼栋确认："来帮忙的人不负责本楼栋"正是这个功能要解决的场景，
  // 每次都弹一遍确认等于把正常操作包装成异常（派单/转派才需要，那是越过了楼栋约束）
  if (!covered && !collaborating) {
    const where = target.buildingName ? `${target.buildingName} ${target.room}` : `工单 ${target.ticketNo}`
    await ElMessageBox.confirm(
      `${candidate?.workerName ?? worker?.realName} 不负责 ${where} 所在的楼栋。这属于跨楼栋${transferring ? '转派' : '强制派单'}（紧急抽调），确认继续？`,
      transferring ? '跨楼栋转派' : '跨楼栋派单',
      { type: 'warning', confirmButtonText: '确认继续', cancelButtonText: '换人' },
    )
  }

  if (transferring) {
    await transferTicket(target.id, workerId, transferReason.value.trim())
    // 转派的计时会重置，提示里把这句说出来：调度员要知道"催单时间重新起算"
    ElMessage.success('已转派；接单计时重新起算')
  } else if (collaborating) {
    await addCollaborator(target.id, workerId)
    ElMessage.success('已加入协作：他能到场、能完工，但不能接单与驳回')
  } else {
    await dispatchTicket(target.id, workerId)
    ElMessage.success(covered ? '派单成功' : '已按紧急任务强制派单')
  }
  dispatchVisible.value = false
  await load()
}

// ==================== 拆单 ====================

const splitVisible = ref(false)
const splitTarget = ref<TicketVO | null>(null)
const splitForm = reactive({ description: '', categoryId: undefined as string | undefined, urgency: undefined as number | undefined })
const categoryOptions = ref<CategoryVO[]>([])

async function openSplit(row: TicketVO) {
  splitTarget.value = row
  splitForm.description = ''
  // 类别与紧急度默认继承原单，但**要能改**：拆出来那件事多半不是同一类
  // （"灯坏了"与"水龙头漏水"）——不能改的话调度员就只能拆出一张类别错的单
  splitForm.categoryId = row.categoryId
  splitForm.urgency = row.urgency
  splitVisible.value = true
  categoryOptions.value = await listCategories(1)
}

async function confirmSplit() {
  const target = splitTarget.value
  if (!target) return
  if (!splitForm.description.trim()) {
    ElMessage.warning('请写清拆出来的是什么问题')
    return
  }
  const created = await splitTicket(target.id, {
    description: splitForm.description.trim(),
    categoryId: splitForm.categoryId,
    urgency: splitForm.urgency,
  })
  splitVisible.value = false
  // 新单是"待派单"：告诉调度员下一步该干什么，而不是让他去列表里找
  ElMessage.success(`已拆出新工单 ${created.ticketNo}（待派单）`)
  await load()
}

/** 从详情抽屉里移除协作者：抽屉与列表都要刷新，否则看到的还是旧名单。 */
async function doRemoveCollaborator(workerId: string, workerName: string) {
  const target = detail.value
  if (!target) return
  await ElMessageBox.confirm(
    `移除协作者 ${workerName}？他将不再看到这张工单。`,
    '移除协作者',
    { type: 'warning' },
  )
  await removeCollaborator(target.id, workerId)
  ElMessage.success('已移除')
  detail.value = await getTicketDetail(target.id)
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

async function doResume(row: TicketVO) {
  await ElMessageBox.confirm(
    `让 ${row.ticketNo} 恢复处理？处理超时按原派单时间继续计算，已超期会立即升级。`,
    '恢复处理',
    { type: 'warning' },
  )
  await resumeTicket(row.id)
  ElMessage.success('已恢复处理')
  await load()
}

// ==================== 合并重复单 ====================

const mergeVisible = ref(false)
const mergeTarget = ref<TicketVO | null>(null)
const mergeTargetId = ref<string | undefined>(undefined)
const mergeRemark = ref('')
const mergeOptions = ref<TicketVO[]>([])
const mergeLoading = ref(false)

/** 可以作为**来源**被合并掉的状态（与 docs/02 §5 一致）；50/60/70/75 不行 */
const MERGEABLE_SOURCES = [10, 20, 30, 35, 40, 45, 80]

async function openMerge(row: TicketVO) {
  mergeTarget.value = row
  mergeTargetId.value = undefined
  mergeRemark.value = ''
  mergeVisible.value = true
  await searchMergeTargets()
}

/**
 * 候选目标：最近 50 张单里排除自己、已撤单与已合并。
 * 演示规模（单校几百单）一页足够；数据量再大时应改成后端按工单号搜索。
 */
async function searchMergeTargets() {
  mergeLoading.value = true
  try {
    const page = await pageTickets({ pageNum: 1, pageSize: 50 })
    mergeOptions.value = page.list.filter(
      (item) => item.id !== mergeTarget.value?.id && ![70, 75].includes(item.status),
    )
  } finally {
    mergeLoading.value = false
  }
}

function mergeOptionLabel(ticket: TicketVO): string {
  return `${ticket.ticketNo} · ${ticket.buildingName} ${ticket.room} · ${STATUS[ticket.status]?.label ?? ticket.status}`
}

async function doMerge() {
  const target = mergeTarget.value
  const targetId = mergeTargetId.value
  if (!target || !targetId) return
  const chosen = mergeOptions.value.find((item) => item.id === targetId)
  await ElMessageBox.confirm(
    `把 ${target.ticketNo} 合并进 ${chosen?.ticketNo ?? '所选工单'}？合并后本单进入终态「已合并」，目标单不变。`,
    '合并工单',
    { type: 'warning', confirmButtonText: '确认合并' },
  )
  await mergeTicket(target.id, targetId, mergeRemark.value.trim() || undefined)
  mergeVisible.value = false
  ElMessage.success('已合并')
  await load()
  if (detail.value?.id === target.id) {
    detail.value = await getTicketDetail(target.id)
  }
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
      <el-table-column label="操作" width="460" fixed="right">
        <template #default="{ row }">
          <el-button link @click="openDetailById(row.id)">详情</el-button>
          <!-- 45 无法维修也是"重新派单"的入口；后端要求必须换人 -->
          <el-button v-if="[10, 45, 80].includes(row.status)" link type="primary" @click="openDispatch(row)">
            派单
          </el-button>
          <!-- 换人：待接单还没接、或接了还没完工时才谈得上换（40 之后该走驳回/打回） -->
          <el-button v-if="[20, 30].includes(row.status)" link type="primary" @click="openTransfer(row)">
            转派
          </el-button>
          <!-- 加协作者：一件活要两个人干（docs/01 §4.5）。10 还没主责、40 之后活已经干完，都没意义 -->
          <el-button v-if="[20, 30].includes(row.status)" link type="primary" @click="openCollaborator(row)">
            协作者
          </el-button>
          <!-- 拆单：一张单里其实是两件事；同样只在活还没干完时可用 -->
          <el-button v-if="[10, 20, 30].includes(row.status)" link @click="openSplit(row)">拆单</el-button>
          <el-button v-if="[20, 30, 40].includes(row.status)" link type="danger" @click="doReject(row)">驳回</el-button>
          <!-- 待料/无法维修：后勤可以直接让单回到处理中（45 的处理超时按原派单时间重算） -->
          <el-button v-if="[35, 45].includes(row.status)" link type="primary" @click="doResume(row)">恢复</el-button>
          <!-- 合并重复单：活动状态都可作为来源；目标在弹窗里选 -->
          <el-button v-if="MERGEABLE_SOURCES.includes(row.status)" link @click="openMerge(row)">合并</el-button>
          <el-button v-if="[45, 50, 80].includes(row.status)" link type="primary" @click="doClose(row)">关闭</el-button>
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
          <el-descriptions-item v-if="detail.assetNo" label="关联设备" :span="2">
            {{ detail.assetNo }} · {{ detail.assetName }}
          </el-descriptions-item>
          <el-descriptions-item label="师傅">{{ detail.workerName ?? '未派单' }}</el-descriptions-item>
          <!-- 协作者：主责不在这个列表里（他就是上面的「师傅」）。
               移除按钮只在 20/30 出现——与后端的状态门槛一致，不做"点了才报错"的按钮 -->
          <el-descriptions-item v-if="detail.collaborators?.length" label="协作者" :span="2">
            <el-tag
              v-for="c in detail.collaborators"
              :key="c.workerId"
              class="collaborator"
              :closable="[20, 30].includes(detail.status)"
              @close="doRemoveCollaborator(c.workerId, c.workerName)"
            >
              {{ c.workerName }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.parentTicketNo" label="拆单来源" :span="2">
            由工单 {{ detail.parentTicketNo }} 拆出
          </el-descriptions-item>
          <el-descriptions-item v-if="detail.mergedIntoNo" label="合并目标" :span="2">
            已合并到工单 {{ detail.mergedIntoNo }}
          </el-descriptions-item>
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

    <el-dialog v-model="dispatchVisible" :title="dialogTitle" width="640px">
      <el-form label-width="80px">
        <el-form-item label="工单">
          <span>
            {{ dispatchTarget?.ticketNo }} · {{ dispatchTarget?.buildingName }} {{ dispatchTarget?.room }}
          </span>
        </el-form-item>
        <el-form-item v-if="dispatchMode === 'transfer'" label="转派理由">
          <el-input
            v-model="transferReason"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
            placeholder="会发给原师傅，例如：张师傅临时请假，改由你上门"
          />
        </el-form-item>
        <el-form-item v-if="dispatchMode === 'dispatch'" label="推荐师傅">
          <el-checkbox
            v-model="includeOffDuty"
            class="off-duty-toggle"
            @change="dispatchTarget && loadCandidates(dispatchTarget.id)"
          >
            显示今天请假/休息的师傅
          </el-checkbox>
          <div v-loading="candidatesLoading" class="candidates">
            <div
              v-for="c in candidates"
              :key="c.workerId"
              :class="['candidate', selectedWorkerId === c.workerId ? 'active' : '']"
              @click="selectedWorkerId = c.workerId"
            >
              <div class="candidate-head">
                <span class="candidate-name">{{ c.workerName }}（{{ c.username }}）</span>
                <el-tag size="small" type="primary">{{ c.score.toFixed(2) }}</el-tag>
                <el-tag v-if="c.responsibleForBuilding" size="small" type="success">负责本楼栋</el-tag>
                <el-tag v-else-if="!c.buildingNames.length" size="small" type="warning">未配楼栋</el-tag>
                <el-tag v-if="c.onDutyToday === true" size="small" type="success">今日在班</el-tag>
                <el-tag v-else-if="c.onDutyToday === false" size="small" type="info">今日请假</el-tag>
              </div>
              <div class="candidate-reason">{{ c.reason }}</div>
              <div class="candidate-bars">
                <div v-for="item in c.items" :key="item.key" class="bar">
                  <span class="bar-label">{{ item.label }}</span>
                  <div class="bar-track"><div class="bar-fill" :style="{ width: `${Math.round(item.score * 100)}%` }" /></div>
                  <span class="bar-score">{{ item.score.toFixed(2) }}</span>
                </div>
              </div>
            </div>
            <div v-if="!candidatesLoading && !candidates.length" class="hint">没有可用的师傅（未维护账号或全部停用）</div>
          </div>
          <div class="hint">
            按「技能 / 满意度 / 负载 / 距离 / 值班」五项加权排序，只是建议——点下面的确认才真正派单
          </div>
        </el-form-item>
        <el-form-item v-else :label="dispatchMode === 'transfer' ? '改派给' : '维修工'">
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
        <el-button
          type="primary"
          :disabled="!selectedWorkerId || (dispatchMode === 'transfer' && !transferReason.trim())"
          @click="confirmDispatch"
        >
          {{ dispatchMode === 'transfer' ? '确认转派' : dispatchMode === 'collaborator' ? '确认加入' : '确认派单' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 拆单：只填"拆出来的是什么" + 类别/紧急度。楼栋房间学生与现场图片都由后端继承原单 -->
    <el-dialog v-model="splitVisible" title="拆单" width="520px">
      <el-form label-width="80px">
        <el-form-item label="原工单">
          <span>
            {{ splitTarget?.ticketNo }} · {{ splitTarget?.buildingName }} {{ splitTarget?.room }}
          </span>
        </el-form-item>
        <el-form-item label="拆出什么">
          <el-input
            v-model="splitForm.description"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="只描述拆出来的那一件事，例如：卫生间水龙头也在漏水"
          />
        </el-form-item>
        <el-form-item label="类别">
          <el-select v-model="splitForm.categoryId" style="width: 100%">
            <el-option v-for="c in categoryOptions" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="紧急度">
          <el-radio-group v-model="splitForm.urgency">
            <el-radio-button :value="1">普通</el-radio-button>
            <el-radio-button :value="2">紧急</el-radio-button>
            <el-radio-button :value="3">特急</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <div class="hint">新工单是「待派单」，位置、学生与现场照片沿用原单；再拆它是拆不动的（只拆一层）。</div>
      </el-form>
      <template #footer>
        <el-button @click="splitVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!splitForm.description.trim()" @click="confirmSplit">
          确认拆单
        </el-button>
      </template>
    </el-dialog>

    <!-- 合并重复单：来源单进入终态，目标单不动。目标只从最近 50 张单里选 -->
    <el-dialog v-model="mergeVisible" title="合并重复工单" width="560px">
      <el-form label-width="90px">
        <el-form-item label="被合并单">
          <span>{{ mergeTarget?.ticketNo }} · {{ mergeTarget?.buildingName }} {{ mergeTarget?.room }}</span>
        </el-form-item>
        <el-form-item label="合并到">
          <el-select
            v-model="mergeTargetId"
            filterable
            :loading="mergeLoading"
            placeholder="选择目标工单（工单号 / 位置）"
            style="width: 100%"
          >
            <el-option v-for="t in mergeOptions" :key="t.id" :label="mergeOptionLabel(t)" :value="t.id" />
          </el-select>
          <div class="hint">
            只列最近 50 张、排除已撤单与已合并的单。合并后本单进入终态「已合并」，目标单不变。
          </div>
        </el-form-item>
        <el-form-item label="说明">
          <el-input
            v-model="mergeRemark"
            type="textarea"
            :rows="2"
            maxlength="255"
            show-word-limit
            placeholder="可选，会进流转日志与通知"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mergeVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!mergeTargetId" @click="doMerge">确认合并</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.candidates {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: 380px;
  overflow-y: auto;
  width: 100%;
}
.candidate {
  padding: 10px 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  cursor: pointer;
  transition: border-color 0.15s, background 0.15s;
}
.candidate:hover {
  border-color: var(--el-color-primary-light-5);
}
.candidate.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
}
.candidate-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}
.candidate-name {
  font-weight: 600;
}
.candidate-reason {
  margin-bottom: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.off-duty-toggle {
  margin-bottom: 8px;
}

.candidate-bars {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 2px 14px;
}
.bar {
  display: flex;
  align-items: center;
  gap: 6px;
}
.bar-label {
  width: 92px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  white-space: nowrap;
}
.bar-track {
  flex: 1;
  height: 6px;
  background: var(--el-fill-color-light);
  border-radius: 3px;
  overflow: hidden;
}
.bar-fill {
  height: 100%;
  background: var(--el-color-primary);
}
.bar-score {
  width: 30px;
  font-size: 12px;
  text-align: right;
  color: var(--el-text-color-regular);
}

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
.collaborator {
  margin-right: 6px;
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
