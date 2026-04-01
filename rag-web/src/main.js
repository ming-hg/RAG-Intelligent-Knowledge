import { createApp } from 'vue'
import './style.css'
import App from './App.vue'
import 'element-plus/dist/index.css'
import router from './router'

// 引入通用组件
import MessageBubble from './components/MessageBubble.vue'
import MarkdownRenderer from './components/MarkdownRenderer.vue'
import FileUpload from './components/FileUpload.vue'
import Loading from './components/Loading.vue'

// 1. 全局引入Element Plus
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
// 2. 引入Element Plus中文语言包（解决组件默认英文问题）
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'

// 3. 全局引入Pinia（状态管理）
import { createPinia } from 'pinia'
const pinia = createPinia()

// 4. 引入封装好的axios实例（后续补充完整代码）
import request from './utils/request'

const app = createApp(App)

// 全局挂载：Element Plus（配置中文）、Pinia、axios
app.use(ElementPlus, { locale: zhCn })
app.use(pinia)
app.use(router)
// 全局挂载axios，组件中可通过this.$request调用

// 全局注册组件
app.component('MessageBubble', MessageBubble)
app.component('MarkdownRenderer', MarkdownRenderer)
app.component('FileUpload', FileUpload)
app.component('Loading', Loading)

app.config.globalProperties.$request = request

app.mount('#app')
