import { http } from './http'
import type { PageResult, StudentImportResult, StudentVO } from '@/types'

export interface StudentQuery {
  pageNum: number
  pageSize: number
  status?: number
  /** 学号或姓名的模糊匹配，服务端过滤 */
  keyword?: string
}

/** 新增：学号即登录名；用户类型由服务端写死为学生，前端不传。该账号首登必须改密。 */
export interface StudentCreateBody {
  username: string
  realName?: string
  phone?: string
  password: string
}

/** 修改：PUT 是"提交最终状态"。口令留空表示不改（填了就是帮学生重置，重置后仍需首登改密）。 */
export interface StudentUpdateBody {
  realName?: string
  phone?: string
  status: number
  password?: string
}

export function pageStudents(query: StudentQuery) {
  return http.get<PageResult<StudentVO>>('/admin/students', { ...query })
}

export function createStudent(body: StudentCreateBody) {
  return http.post<StudentVO>('/admin/students', body)
}

export function updateStudent(id: string, body: StudentUpdateBody) {
  return http.put<void>(`/admin/students/${id}`, body)
}

/** 重置口令：重置后该学生下次登录仍会被要求改密。 */
export function resetStudentPassword(id: string, password: string) {
  return http.put<void>(`/admin/students/${id}/password`, { password })
}

/**
 * 批量导入：粘贴学号名单，每行一个（支持「学号,姓名」）。
 * 已存在的学号会被跳过而不是报错——学校补录时名单里必然带着上次已导的人。
 */
export function importStudents(rows: string, password: string) {
  return http.post<StudentImportResult>('/admin/students/import', { rows, password })
}
