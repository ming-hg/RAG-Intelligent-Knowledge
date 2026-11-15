import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // 走代理而不是在浏览器里直连 8080：既免掉 CORS 配置，也让「后端换端口」只改一个环境变量
  const target = env.VITE_API_TARGET ?? 'http://localhost:8080'

  return {
    plugins: [vue()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      proxy: {
        '/api': { target, changeOrigin: true },
      },
    },
    build: {
      chunkSizeWarningLimit: 1200,
    },
  }
})
