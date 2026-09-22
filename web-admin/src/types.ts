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
  /** 1学生 2维修工 3后勤管理 4平台运营 */
  userType: number
  /**
   * true = 还在用管理员 / 平台设的初始口令，必须先去改密。
   * 服务端也拦着（未改密时只放行 `/api/auth/**`），前端拿它决定"直接把改密框弹出来"，
   * 否则用户会看到一屏 10003 却不知道去哪改。
   */
  mustChangePassword: boolean
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
  dispatchTime: string | null
  finishTime: string | null
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

/** 维修工（docs/03 §5.4 基础数据）。buildingIds / buildingNames 是"他负责的楼栋"，即数据可见范围的依据。 */
export interface WorkerVO {
  id: string
  /** 工号，同时是登录名 */
  username: string
  realName: string
  phone: string | null
  /** 1启用 0停用 */
  status: number
  buildingIds: string[]
  buildingNames: string[]
}

export interface StudentVO {
  id: string
  /** 学号，同时是登录名 */
  username: string
  realName: string | null
  phone: string | null
  /** 1启用 0停用 */
  status: number
  /** true = 还在用管理员设的初始口令，该学生首次登录会被要求改密 */
  mustChangePassword: boolean
}

/** 批量导入结果。跳过不是错误：补录名单里必然带着上次已导的人。 */
export interface StudentImportResult {
  created: number
  skipped: number
  /** 被跳过的学号，便于核对是不是导错了名单 */
  skippedUsernames: string[]
  /** 名单里被忽略的空行 / 批次内重复行 */
  ignored: number
}

/** 楼栋（docs/03 §5.4 基础数据）。列表不分页：字典数据量小，下拉要一次拿全。 */
export interface BuildingVO {
  id: string
  name: string
  area: string | null
  sort: number
  /** 1启用 0停用 */
  status: number
}

/** 报修类别（docs/03 §5.4 基础数据）。defaultUrgency 是学生没选紧急度时的默认值。 */
export interface CategoryVO {
  id: string
  name: string
  /** 1普通 2紧急 3特急 */
  defaultUrgency: number
  sort: number
  /** 1启用 0停用 */
  status: number
}

/**
 * 报修码记录（管理端）。注意与"扫码返回的位置"区分：这里带主键、码本身的状态与生成时间，
 * 改码时用的是 id（不是 code 字符串）。码是位置码，一张码 = 一个房间门口的那张。
 */
export interface RepairCodeDetailVO {
  id: string
  /** 6 位数字 */
  code: string
  buildingId: string
  buildingName: string
  room: string
  /** 1启用 0停用 */
  status: number
  createTime: string
}

/** 工单流转日志（详情页的时间线）。action 是后端 TicketAction 的枚举名。 */
export interface TicketLogVO {
  id: string
  action: string
  fromStatus: number | null
  toStatus: number | null
  operatorId: string | null
  operatorName: string | null
  remark: string | null
  createTime: string
}

export interface TicketEvaluationVO {
  id: string
  score: number
  content: string | null
  createTime: string
}

/** 工单详情：比列表多出描述、图片、维修结果、驳回原因与流转时间线。 */
export interface TicketDetailVO extends TicketVO {
  description: string | null
  images: string[] | null
  resultDesc: string | null
  resultImages: string[] | null
  rejectReason: string | null
  acceptTime: string | null
  arriveTime: string | null
  closeTime: string | null
  logs: TicketLogVO[]
  /** 未评价时为 null */
  evaluation: TicketEvaluationVO | null
}

/** 站内通知。title / content 由后端按动作规格生成，前端直接展示即可。 */
export interface NotificationVO {
  id: string
  type: string
  title: string
  content: string | null
  ticketId: string | null
  /** 0未读 1已读 */
  isRead: number
  createTime: string
}

/**
 * 学校（租户）。只有平台运营看得到（docs/03 §5.5）——平台看不到任何学校的业务数据。
 * 列表里**不含平台自身**（`tenant.id = 0` 那一行是平台，不是学校）。
 */
export interface TenantVO {
  id: string
  name: string
  /** 学校编码，师生登录时填的那个 */
  code: string
  contact: string | null
  phone: string | null
  /** 1启用 0停用。停用会踢掉该校全部在线用户，之后他们也登不进来 */
  status: number
  createTime: string
}

/** 某所学校的后勤管理员。不含口令——任何接口都不返回口令。 */
export interface TenantAdminVO {
  id: string
  username: string
  realName: string | null
  phone: string | null
  /** 1启用 0停用 */
  status: number
  /** true = 还在用初始口令，说明这个账号还没被激活过 */
  mustChangePassword: boolean
}
