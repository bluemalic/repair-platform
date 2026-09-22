import { http } from './http'
import type { PageResult, TenantAdminVO, TenantVO } from '@/types'

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
}

/** 改资料：编码不给改（它是师生天天输入的登录参数）。 */
export interface TenantUpdateBody {
  name: string
  contact?: string
  phone?: string
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
