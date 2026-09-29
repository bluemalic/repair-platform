import { http } from './http'
import type { PageResult, TicketDetailVO, TicketVO } from '@/types'

export interface TicketQuery {
  pageNum: number
  pageSize: number
  status?: number
  buildingId?: string
  categoryId?: string
}

export function pageTickets(query: TicketQuery) {
  return http.get<PageResult<TicketVO>>('/admin/tickets', { ...query })
}

/** 详情：含流转时间线、验收评价与图片 */
export function getTicketDetail(id: string) {
  return http.get<TicketDetailVO>(`/admin/tickets/${id}`)
}

export function dispatchTicket(id: string, workerId: string) {
  return http.post<void>(`/admin/tickets/${id}/dispatch`, { workerId })
}

/**
 * 转派：换个人做，单不退（`docs/01` §4.1）。
 *
 * 理由必填——这条动作会让原师傅手上的活突然消失，他得知道为什么。后端还会把计时重置
 * （`dispatch_time` 归零、上一轮的接单/到场时间清空），不能让新师傅背前一个人的延迟。
 */
export function transferTicket(id: string, workerId: string, reason: string) {
  return http.post<void>(`/admin/tickets/${id}/transfer`, { workerId, reason })
}

export function rejectTicket(id: string, reason: string) {
  return http.post<void>(`/admin/tickets/${id}/reject`, { reason })
}

export function closeTicket(id: string) {
  return http.post<void>(`/admin/tickets/${id}/close`)
}

/**
 * 加协作者：一件活要两个人干时，把另一个师傅拉进来（`docs/01` §4.5）。
 *
 * 待接单/处理中才能加，最多 3 人；被加的人能到场、能完工，但不能接单 / 驳回 / 转派。
 * 与派单不同，这里**不做跨楼栋确认**——"来帮忙的人不负责本楼栋"正是这个功能要解决的场景。
 */
export function addCollaborator(id: string, workerId: string) {
  return http.post<void>(`/admin/tickets/${id}/collaborators`, { workerId })
}

/** 移除协作者：被移除的师傅随之看不到这单（留痕在工单时间线里）。 */
export function removeCollaborator(id: string, workerId: string) {
  return http.del<void>(`/admin/tickets/${id}/collaborators/${workerId}`)
}

/**
 * 拆单：一张单里其实是两件事时，拆出一张**待派单的新单**（`docs/01` §4.5）。
 *
 * 新单继承原单的楼栋 / 房间 / 学生 / 现场图片，描述必填；类别与紧急度默认继承原单、可改。
 * 只拆一层；返回新工单，用来把新单号提示给调度员——派谁去修是独立的一步。
 */
export function splitTicket(
  id: string,
  body: { description: string; categoryId?: string; urgency?: number },
) {
  return http.post<TicketVO>(`/admin/tickets/${id}/split`, body)
}
