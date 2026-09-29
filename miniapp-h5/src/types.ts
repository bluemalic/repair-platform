/** 后端统一返回体（docs/03）：业务失败也是 HTTP 200，靠 code 判断。 */
export interface Result<T> {
  code: number
  message: string
  data: T
  traceId: string
}

/** 分页返回体：ID 是字符串（雪花 ID 超出 JS 安全整数），计数是数字。 */
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
  /** 1学生 2维修工 3后勤管理（移动端只有前两种，后勤用 web-admin） */
  userType: number
  /** true = 还在用初始口令，登录后必须先去改密 */
  mustChangePassword?: boolean
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
  /**
   * 当前登录人是不是这单的协作者（`docs/01` §4.5）。
   *
   * **只有「我的任务」列表会填**（那里要把"派给我的"与"我协作的"分开显示），其它列表是 null——
   * 别把它当 `false` 用，否则别的地方会凭空长出一个「协作」标记。
   */
  collaborative?: boolean | null
  submitTime: string
  /** 后端列表里就有这两个时间，Vue 侧要用到（详情里显示"派单/完工"时刻） */
  dispatchTime: string | null
  finishTime: string | null
  arriveMinutes: number | null
  handleMinutes: number | null
}

/** 报修类别（各端共用的只读接口 `/api/categories` 只返回启用中的）。 */
export interface CategoryVO {
  id: string
  name: string
  /** 1普通 2紧急 3特急 */
  defaultUrgency: number
  sort: number
  status: number
}

/** 按报修码查到的位置（学生报修预填、维修工到场比对都用它）。 */
export interface RepairCodeVO {
  buildingId: string
  buildingName: string
  room: string
}

export interface TicketLogVO {
  id: string
  action: string
  fromStatus: number | null
  toStatus: number | null
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

/** 工单详情：比列表多出描述、图片、维修结果与流转时间线。 */
export interface TicketDetailVO extends TicketVO {
  description: string | null
  images: string[] | null
  resultDesc: string | null
  resultImages: string[] | null
  rejectReason: string | null
  acceptTime: string | null
  arriveTime: string | null
  closeTime: string | null
  /** 协作者（多人同做一单）。**主责不在这个列表里**——他是 workerId / workerName */
  collaborators: TicketCollaboratorVO[]
  workerName: string | null
  /** 拆单来源（非拆单产生的为 null） */
  parentTicketNo: string | null
  logs: TicketLogVO[]
  evaluation: TicketEvaluationVO | null
}

/** 协作者（只读展示，加/移由后勤在管理端做）。 */
export interface TicketCollaboratorVO {
  workerId: string
  workerName: string
}

/** 上传结果（docs/03 §7.3）。 */
export interface FileUploadVO {
  url: string
  objectKey: string
}
