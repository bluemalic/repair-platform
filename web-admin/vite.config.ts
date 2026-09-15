import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    port: 5173,
    // 开发期把 /api 代理到本机后端（8080），生产由 nginx 转发——前端不关心后端地址。
    // 8080 被占用（例如 IDEA 里已经跑着一个实例）时，用 VITE_API_TARGET 指到别的端口。
    proxy: {
      '/api': {
        target: process.env.VITE_API_TARGET ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
