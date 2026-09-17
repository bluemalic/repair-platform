import { request } from './http'
import type { PageResult, TicketVO } from '@/types'

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
