import { request } from './http'
import type { PageResult, RepairCodeVO, TicketDetailVO, TicketVO } from '@/types'

/** 学生：我的报修（可见范围由后端数据权限拦截器按"本人"注入） */
export function pageMyTickets(pageNum: number, pageSize = 10) {
  return request<PageResult<TicketVO>>({
    url: '/student/tickets',
    data: { pageNum, pageSize },
  })
}

/** 维修工：我的任务（可见范围按"负责楼栋"注入，与接单列表是同一份数据） */
export function pageMyTasks(pageNum: number, pageSize = 10) {
  return request<PageResult<TicketVO>>({
    url: '/worker/tickets',
    data: { pageNum, pageSize },
  })
}

/** 按报修码查位置（跨端共用接口，docs/03 §7.1）；码是位置码，不指向某张工单。 */
export function getPositionByCode(code: string) {
  return request<RepairCodeVO>({ url: `/tickets/by-code/${code}` })
}

/** 提交报修：带报修码，楼栋房间由服务端从码里取（前端不传）。 */
export function createTicket(body: {
  repairCode: string
  categoryId: string
  description: string
  images?: string[]
  urgency?: number
}) {
  return request<TicketVO>({ url: '/student/tickets', method: 'POST', data: body })
}

export function getTicketDetail(id: string) {
  return request<TicketDetailVO>({ url: `/student/tickets/${id}` })
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
