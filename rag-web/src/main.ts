import { createApp } from 'vue'
import { createPinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import './styles/app.css'

const pinia = createPinia()
// 只持久化界面偏好（选中的库、模式、topK），会话与消息永远以数据库为准
pinia.use(piniaPluginPersistedstate)

createApp(App).use(pinia).use(router).use(ElementPlus, { locale: zhCn }).mount('#app')
