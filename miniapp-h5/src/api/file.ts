import { getToken } from '@/store/auth'
import type { FileUploadVO, Result } from '@/types'

const BASE_URL = import.meta.env.VITE_API_BASE ?? '/api'

/**
 * 上传图片。
 *
 * <p>用 `uni.uploadFile` 而不是 `uni.request`：后者发 JSON，multipart 得靠它。
 * 表单字段名固定 `file`（与后端 `@RequestPart("file")` 对齐，docs/03 §7.3）。
 *
 * <p>注意它不走 `http.ts` 里的统一错误处理——`uni.uploadFile` 只给一个字符串 body，
 * 所以这里自己解 `Result` 并弹提示。抽公共逻辑反而要为一个接口破例，不值。
 */
export function uploadImage(filePath: string): Promise<FileUploadVO> {
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: BASE_URL + '/files/upload',
      filePath,
      name: 'file',
      header: { Authorization: `Bearer ${getToken()}` },
      success: (res) => {
        let body: Result<FileUploadVO> | undefined
        try {
          body = JSON.parse(res.data) as Result<FileUploadVO>
        } catch {
          uni.showToast({ title: '上传失败：响应无法解析', icon: 'none' })
          reject(new Error('bad response'))
          return
        }
        if (body.code === 0) {
          resolve(body.data)
          return
        }
        uni.showToast({ title: body.message || '上传失败', icon: 'none' })
        reject(new Error(body.message))
      },
      fail: () => {
        uni.showToast({ title: '上传失败，请检查网络', icon: 'none' })
        reject(new Error('upload failed'))
      },
    })
  })
}
