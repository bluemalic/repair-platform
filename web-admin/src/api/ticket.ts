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
