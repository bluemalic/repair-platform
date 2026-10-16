import { request } from '@/api/http'
import type { AgentIntakeVO } from '@/types'

/**
 * 报修受理 Agent（docs/03 §5.2）。
 *
 * <p>**它不落单**：返回的是草稿与追问，落单仍走 {@link createTicket}——权限、租户校验、审计
 * 都在那条链上（ADR-017）。所以这个页面"确认提交"时调的是同一个提交接口，不是新开一条写路径。
 *
 * <p>`repairCode` 只作为**位置上下文**交给助手（让它别再问楼栋房间），而不是让助手去查码——
 * 报修码是防枚举面，查询由前端这一步完成（用户在界面上扫码/手输）。
 */
export function intake(message: string, sessionId?: string, repairCode?: string) {
  return request<AgentIntakeVO>({
    url: '/student/agent/intake',
    method: 'POST',
    data: { message, sessionId, repairCode },
  })
}
