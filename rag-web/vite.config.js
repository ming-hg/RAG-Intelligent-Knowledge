import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path' // 需要引入 node 的 path 模块

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      // 将 @ 映射到 src 目录的绝对路径
      '@': path.resolve(__dirname, './src'),
    },
  },
})
