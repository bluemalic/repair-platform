import { request } from '@/api/http'
import type { LocationVO } from '@/types'

/**
 * 可报修的位置（docs/01 §4.6、docs/03 §5.1）。
 *
 * <p>用途只有一个：**公共区域没有码可扫**。宿舍报修扫码最快，但走廊灯坏了、大厅地面滑，
 * 不可能为了报修去满楼找码，所以报修页还有一个"手动选位置"的入口。
 *
 * <p>返回的每一个位置都**有报修码**——这不是巧合：师傅到场要扫同一张码核验，
 * 没码的位置报了也验不了到场。所以这份清单本质上就是报修码清单的人话版。
 */
export function getLocations() {
  return request<LocationVO[]>({ url: '/locations' })
}
