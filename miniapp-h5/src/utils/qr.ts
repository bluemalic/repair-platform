import jsQR from 'jsqr'

/**
 * 一帧像素 → 二维码里的字符串（没有识别到返回 null）。
 *
 * <p>抽成纯函数：解码逻辑与"摄像头怎么开、帧从哪来"分开，扫码页那边只管取帧。
 * 用的 `jsqr` 是**纯解码**库，不是那种连 UI 一起给的相机组件——界面我们自己做
 * （uni-app 里也不该塞别人家的 DOM 组件，小程序端更没有 DOM）。
 *
 * <p>`inversionAttempts: 'dontInvert'`：不去试反色。门口的码都是深色点印在白纸上，
 * 试反色只会让每帧多花一倍时间，手机上不值得。
 */
export function decodeQrFrame(imageData: ImageData): string | null {
  const result = jsQR(imageData.data, imageData.width, imageData.height, {
    inversionAttempts: 'dontInvert',
  })
  return result?.data ?? null
}
