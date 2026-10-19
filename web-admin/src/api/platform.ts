import { http } from './http'
import type { PageResult, TenantAdminVO, TenantSsoConfig, TenantVO } from '@/types'

/**
 * 平台运营端（docs/03 §5.5）。
 *
 * 这一组接口的调用者是**平台运营账号**（`tenant_id = 0`、`user_type = 4`），它不属于任何学校，
 * 也看不到任何学校的业务数据——平台账号调 `/api/admin/**` 一律 10003（ADR-012）。
 */

export interface TenantQuery {
  pageNum: number
  pageSize: number
  status?: number
  /** 学校名称或编码的模糊匹配，服务端过滤 */
  keyword?: string
}

/** 开通学校：学校信息 + 它的第一个后勤管理员，服务端在同一个事务里建。 */
export interface TenantCreateBody {
  name: string
  /** 登录参数的一部分，2-32 位小写字母 / 数字 / 连字符 */
  code: string
  contact?: string
  phone?: string
  adminUsername: string
  adminRealName?: string
  adminPhone?: string
  adminPassword: string
  /** 学生账号上限；留空用服务端默认 200，0 = 不限 */
  maxStudents?: number
  /** 维修工账号上限；留空用服务端默认 20，0 = 不限 */
  maxWorkers?: number
}

/** 改资料：编码不给改（它是师生天天输入的登录参数）。 */
export interface TenantUpdateBody {
  name: string
  contact?: string
  phone?: string
  /** 账号配额；不传表示不改。0 = 不限 */
  maxStudents?: number
  maxWorkers?: number
}

export interface TenantAdminCreateBody {
  username: string
  realName?: string
  phone?: string
  password: string
}

export function pageTenants(query: TenantQuery) {
  return http.get<PageResult<TenantVO>>('/platform/tenants', { ...query })
}

export function createTenant(body: TenantCreateBody) {
  return http.post<TenantVO>('/platform/tenants', body)
}

export function updateTenant(id: string, body: TenantUpdateBody) {
  return http.put<void>(`/platform/tenants/${id}`, body)
}

/** 启停学校。**停用会踢掉该校全部在线用户**，之后他们也无法登录。 */
export function changeTenantStatus(id: string, status: number) {
  return http.put<void>(`/platform/tenants/${id}/status`, { status })
}

export function listTenantAdmins(tenantId: string) {
  return http.get<TenantAdminVO[]>(`/platform/tenants/${tenantId}/admins`)
}

/**
 * 给已有学校加一个后勤管理员（不新建学校）。
 * 用途：学校只有一个管理员，那人离职或长期请假后没人能派单、没人能维护账号。
 */
export function addTenantAdmin(tenantId: string, body: TenantAdminCreateBody) {
  return http.post<TenantAdminVO>(`/platform/tenants/${tenantId}/admins`, body)
}

/** 重置管理员口令：这是"后勤管理员忘记口令"的唯一出路，重置后他下次登录必须改密。 */
export function resetTenantAdminPassword(tenantId: string, userId: string, password: string) {
  return http.put<void>(`/platform/tenants/${tenantId}/admins/${userId}/password`, { password })
}

/**
 * 学校的统一认证配置（阶段 7 · ADR-022）。
 *
 * 必填项由后端按 `protocol` 校验（OAuth2 要三个地址 + clientId；CAS 要两个地址）；
 * **启用时至少要有一个登录后的跳转地址**。
 */
export interface TenantSsoConfigBody {
  /** 1 启用 / 0 停用 */
  enabled: number
  protocol: 'OAUTH2' | 'CAS'
  // OAuth2：authorizeUrl 是浏览器跳转的（必须外网可达）；tokenUrl / userinfoUrl 是服务端调用的
  authorizeUrl?: string
  tokenUrl?: string
  userinfoUrl?: string
  clientId?: string
  /** **留空表示不修改**（后端不会把已配置的密钥清掉） */
  clientSecret?: string
  scope?: string
  // CAS
  casLoginUrl?: string
  casValidateUrl?: string
  /** IdP 返回的哪个属性是登录名（学号 / 工号） */
  usernameAttribute?: string
  /** 登录成功后跳回前端：只允许用这里配的（防开放重定向） */
  adminRedirectUrl?: string
  h5RedirectUrl?: string
}

export function getTenantSso(tenantId: string) {
  return http.get<TenantSsoConfig>(`/platform/tenants/${tenantId}/sso`)
}

export function updateTenantSso(tenantId: string, body: TenantSsoConfigBody) {
  return http.put<TenantSsoConfig>(`/platform/tenants/${tenantId}/sso`, body)
}
