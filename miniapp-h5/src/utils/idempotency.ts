/**
 * 提交幂等键：同一张报修草稿的重试必须复用同一个值，提交成功后换新（本项目的页面提交成功后会
 * reLaunch，新页面实例自然拿到新 key）。
 *
 * <p>不用 `crypto.randomUUID`：小程序端没有它；这个值只用于服务端去重，不需要密码学强度。
 */
export function newIdempotencyKey(): string {
  return `${Date.now()}-${Math.random().toString(16).slice(2)}-${Math.random().toString(16).slice(2)}`
}
