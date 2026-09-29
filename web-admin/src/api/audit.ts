import { http } from './http'
import type { AuditLogVO, PageResult } from '@/types'

/** 操作日志的筛选条件（都可不填）。 */
export interface AuditLogQuery {
  pageNum: number
  pageSize: number
  /** 动作码精确筛选，如 WORKER_CREATE */
  action?: string
  /** 操作人姓名关键字 */
  operatorKeyword?: string
  /** 起始日期 yyyy-MM-dd（含） */
  startDate?: string
  /** 结束日期 yyyy-MM-dd（含） */
  endDate?: string
  /**
   * 是否包含登录事件，默认 false。
   *
   * 这个页面的主查询是「谁改了东西」，而登录记录会占绝大多数（H5 每次冷启动都要登录），
   * 所以默认不显示。显式指定了 `action` 时它不起作用——那时按动作筛就是了。
   */
  includeLogin?: boolean
}

/**
 * 操作日志（`docs/03` §5.4）：**本租户**的账号与基础数据写操作 + 登录事件，时间倒序。
 *
 * 工单流转不在这里——它有自己的时间线（工单详情里那一条）。
 */
export function pageAuditLogs(query: AuditLogQuery) {
  return http.get<PageResult<AuditLogVO>>('/admin/audit-logs', {
    pageNum: query.pageNum,
    pageSize: query.pageSize,
    action: query.action,
    operatorKeyword: query.operatorKeyword,
    startDate: query.startDate,
    endDate: query.endDate,
    includeLogin: query.includeLogin,
  })
}
