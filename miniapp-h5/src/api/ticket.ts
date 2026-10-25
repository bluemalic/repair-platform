import { request } from './http'
import type { KnowledgeAdviceVO, PageResult, RepairCodeVO, TicketDetailVO, TicketVO } from '@/types'

/** 学生：我的报修（可见范围由后端数据权限拦截器按"本人"注入） */
export function pageMyTickets(pageNum: number, pageSize = 10) {
  return request<PageResult<TicketVO>>({
    url: '/student/tickets',
    data: { pageNum, pageSize },
  })
}

/** 维修工：我的任务（可见范围按"负责楼栋"注入，与接单列表是同一份数据） */
/**
 * 维修工的任务列表。
 *
 * `scope` 是两个**业务视图**（`docs/01` §4.2）：`mine`（默认）只给派给我的单、
 * 默认状态为进行中；`building` 给我负责楼栋的全部工单（含终态与别人负责的）。
 * 真正的可见范围由后端的数据权限拦截器保证，前端传什么都越不过它。
 *
 * `status` 显式传时会覆盖视图的默认状态过滤（用来看历史）。
 */
export function pageMyTasks(
  pageNum: number,
  pageSize = 10,
  scope: 'mine' | 'building' = 'mine',
  status?: number,
) {
  return request<PageResult<TicketVO>>({
    url: '/worker/tickets',
    data: { pageNum, pageSize, scope, status },
  })
}

/** 按报修码查位置（跨端共用接口，docs/03 §7.1）；码是位置码，不指向某张工单。 */
export function getPositionByCode(code: string) {
  return request<RepairCodeVO>({ url: `/tickets/by-code/${code}` })
}

/**
 * 提交报修。位置有两条路，二选一（docs/01 §4.6）：
 * · `repairCode`：扫码/手输码，楼栋房间由**服务端**从码里取（不信任前端传的位置）
 * · `buildingId + room`：手动选位置——公共区域没有码可扫时走这条
 *
 * 两条路落到同一张工单表、同一套到场核验：位置清单里的每个位置都有一张码。
 */
export function createTicket(body: {
  /** 客户端生成的幂等键：重试必须复用同一个值，同 key 只会产生一张工单 */
  idempotencyKey: string
  repairCode?: string
  buildingId?: string
  room?: string
  categoryId: string
  description: string
  images?: string[]
  urgency?: number
}) {
  return request<TicketVO>({ url: '/student/tickets', method: 'POST', data: body })
}

/**
 * 学生看自己的工单详情。
 *
 * <p>**维修工不能用它**：这条路径属于 `/api/student/**`，权限码是 `ticket:list:self`，
 * 维修工调会 403（真机验证时踩到过）。两端各有一个详情接口，服务端是同一个 service 方法，
 * 差别只在路由与权限；数据可见范围仍由拦截器按角色注入。
 */
export function getTicketDetail(id: string) {
  return request<TicketDetailVO>({ url: `/student/tickets/${id}` })
}

/** 维修工看派给自己楼栋的工单详情。 */
export function getWorkerTicketDetail(id: string) {
  return request<TicketDetailVO>({ url: `/worker/tickets/${id}` })
}

/** 撤单：只有"待派单"可以撤（10 → 70）。 */
export function cancelTicket(id: string) {
  return request<void>({ url: `/student/tickets/${id}/cancel`, method: 'POST' })
}

// ==================== 维修工动作 ====================

/** 接单：待接单 → 处理中；被别人抢先返回 20003。 */
export function acceptTicket(id: string) {
  return request<void>({ url: `/worker/tickets/${id}/accept`, method: 'POST' })
}

/** 驳回：待接单 / 处理中 → 已驳回（可被重新派单）。 */
export function rejectTicket(id: string, reason: string) {
  return request<void>({ url: `/worker/tickets/${id}/reject`, method: 'POST', data: { reason } })
}

/** 到场打卡：服务端会校验码与工单的楼栋房间一致，不一致返回 20007。 */
export function arriveTicket(id: string, repairCode: string) {
  return request<void>({ url: `/worker/tickets/${id}/arrive`, method: 'POST', data: { repairCode } })
}

/** 完工上报：处理中 → 待验收。 */
export function finishTicket(id: string, resultDesc: string, resultImages?: string[]) {
  return request<void>({
    url: `/worker/tickets/${id}/finish`,
    method: 'POST',
    data: { resultDesc, resultImages },
  })
}

/** 验收评价：待验收 → 已完成（40 → 50）。评分 1-5 必填，留言选填。 */
export function evaluateTicket(id: string, score: number, content?: string) {
  return request<void>({
    url: `/student/tickets/${id}/evaluate`,
    method: 'POST',
    data: { score, content },
  })
}

/**
 * 验收不通过：打回重做（40 → 30），`docs/01` §4.1。
 *
 * 与「驳回」不是一回事：驳回清空派单、退回调度池；这里是**还是这位师傅返工**（保留 worker_id）。
 * 理由必填——不写理由，师傅只能猜哪里没做好。
 */
export function reworkTicket(id: string, reason: string) {
  return request<void>({
    url: `/student/tickets/${id}/rework`,
    method: 'POST',
    data: { reason },
  })
}

/**
 * 维修工：某张工单的「AI 排查建议」（阶段 2 · ADR-018）。
 *
 * <p>建议的**依据是本校知识库里检索到的条目**：命中的条目与每一步的引用都随响应回来，
 * 界面上要显示出来——师傅据此判断这条建议值不值得信。知识库里没有相关条目时
 * 后端不会再调模型，`generated=false` 并给一句说明。
 *
 * <p>它不写库：看完怎么做仍然是师傅的判断。
 */
export function getAdvice(ticketId: string) {
  return request<KnowledgeAdviceVO>({
    url: `/worker/tickets/${ticketId}/advice`,
    method: 'POST',
  })
}
