import { defineConfig } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

/**
 * 移动端（学生 / 维修工）工程。与 web-admin **相互独立**：各自 package.json 与 lockfile、
 * 不共享源码、不搞根目录 workspace（AGENTS §3.1、ADR-007）——所以这里的 vite 是 5.2.8，
 * 而 web-admin 是 8.x，两边互不影响。
 *
 * vite 版本不是随便挑的：`@dcloudio/vite-plugin-uni` 的 peerDependency 精确钉在 5.2.8，
 * 它的 @vue/compiler-sfc 也钉在 3.4.21，所以 package.json 里 vue 也必须对齐 3.4.21
 * （编译器与运行时不同版本会出现"能编译、运行时行为不一致"的怪问题）。
 */
export default defineConfig({
  plugins: [uni()],
  server: {
    port: 5175,
    // H5 开发时走 vite 代理；线上由 nginx 把 /api 反代到后端（与 web-admin 同一套思路）
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
