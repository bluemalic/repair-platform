import { http } from './http'
import type { NotificationVO, PageResult } from '@/types'

export interface NotificationQuery {
  pageNum: number
  pageSize: number
  /** 0 只看未读 / 1 只看已读；不传为全部 */
  isRead?: number
}

export function pageNotifications(query: NotificationQuery) {
  return http.get<PageResult<NotificationVO>>('/notifications', { ...query })
}

/** 未读数。后端特意返回数字（不是字符串），前端角标直接用。 */
export function unreadCount() {
  return http.get<number>('/notifications/unread-count')
}

/** 标记单条已读；不是自己的通知会返回 10006（后端不区分"不存在"与"不是你的"）。 */
export function markNotificationRead(id: string) {
  return http.put<void>(`/notifications/${id}/read`)
}

export function markAllNotificationsRead() {
  return http.put<void>('/notifications/read-all')
}
