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

export function rejectTicket(id: string, reason: string) {
  return http.post<void>(`/admin/tickets/${id}/reject`, { reason })
}

export function closeTicket(id: string) {
  return http.post<void>(`/admin/tickets/${id}/close`)
}
