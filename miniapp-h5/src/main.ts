import { createSSRApp } from 'vue'
import App from './App.vue'

/**
 * uni-app 的 Vue3 入口固定是 `createSSRApp` + 导出 `createApp` 工厂：
 * 小程序端用同一套代码做同构渲染，换成 `createApp` 在小程序里起不来。
 * 现在只编 H5，但这行代码是"将来能编小程序"的前提。
 */
export function createApp() {
  const app = createSSRApp(App)
  return { app }
}
