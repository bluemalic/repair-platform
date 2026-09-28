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
}

/**
 * 操作日志（`docs/03` §5.4）：**本租户**的账号与基础数据写操作，时间倒序。
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
  })
}
