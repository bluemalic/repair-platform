/** 后端统一返回体（docs/03）：业务失败也是 HTTP 200，靠 code 判断。 */
export interface Result<T> {
  code: number
  message: string
  data: T
  traceId: string
}

/** 分页返回体：ID 是字符串（雪花 ID 超出 JS 安全整数），计数是数字（前端分页组件要求）。 */
export interface PageResult<T> {
  total: number
  pageNum: number
  pageSize: number
  pages: number
  list: T[]
}

export interface LoginVO {
  tokenName: string
  tokenValue: string
  userId: string
  username: string
  realName: string
  /** 1学生 2维修工 3后勤管理 */
  userType: number
}

export interface TicketVO {
  id: string
  ticketNo: string
  /** 10待派单 20待接单 30处理中 40待验收 50已完成 60已关闭 70已撤单 80已驳回 */
  status: number
  /** 1普通 2紧急 3特急 */
  urgency: number
  buildingName: string
  room: string
  categoryName: string
  workerId: string | null
  submitTime: string
  arriveMinutes: number | null
  handleMinutes: number | null
}

export interface StatisticsOverview {
  total: number
  avgResponseMinutes: number | null
  avgHandleMinutes: number | null
  timeoutCount: number
  timeoutRate: number
  avgScore: number | null
}

export interface TrendPoint {
  date: string
  count: number
}

export interface DistributionItem {
  name: string
  count: number
}

export interface WorkerWorkload {
  workerId: string
  workerName: string
  finishedCount: number
  avgHandleMinutes: number | null
  processTimeoutCount: number
  onTimeRate: number | null
}
