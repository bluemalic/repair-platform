import { ref } from 'vue'
import { unreadCount } from '@/api/notification'

/**
 * 未读数：侧边栏角标与通知页共用一份。
 *
 * 为什么要单独放一个 store：角标在 LayoutView、标记已读在通知页，两处各拉各的就会出现
 * "刚点完已读，角标还是旧的"。放在这里谁改完谁调 `refreshUnread()`。
 */
export const unread = ref(0)

/** 拉一次未读数。失败不抛给调用方——角标是锦上添花，不该让它影响页面加载。 */
export async function refreshUnread(): Promise<void> {
  try {
    unread.value = await unreadCount()
  } catch {
    // http.ts 已经弹过提示（含 401 清登录态），这里保持上一次的值即可
  }
}
