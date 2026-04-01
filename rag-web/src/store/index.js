import { createPinia } from 'pinia'
// 引入持久化插件（可选，实现会话状态本地存储，避免刷新丢失）
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'

const pinia = createPinia()
// 安装持久化插件
pinia.use(piniaPluginPersistedstate)

export default pinia

