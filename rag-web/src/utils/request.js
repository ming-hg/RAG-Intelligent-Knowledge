// 前端axios封装，统一配置基础地址、请求拦截、响应拦截
import axios from 'axios';
import { ElMessage, ElLoading } from 'element-plus'
// 引入EventSource polyfill，兼容低版本浏览器
import 'event-source-polyfill'

// 创建axios实例，配置后端基础地址（默认后端地址http://localhost:3000）
const BASEURL = 'http://localhost:3000' // 后端服务地址，与后端端口一致
const request = axios.create({
  baseURL: BASEURL, // 后端服务地址，与后端端口一致
  timeout: 10000, // 请求超时时间（10秒）
  headers: {
    'Content-Type': 'application/json' // 默认请求头
  }
});

// 2. 请求拦截器（添加加载状态、请求头）
let loadingInstance = null
request.interceptors.request.use(
  (config) => {
    // 非流式请求，显示全局加载状态
    if (!config.headers['stream']) {
      loadingInstance = ElLoading.service({
        text: '处理中...',
        background: 'rgba(0, 0, 0, 0.3)'
      })
    }
    return config
  },
  (error) => {
    if (loadingInstance) loadingInstance.close()
    ElMessage.error('请求发起失败，请检查网络')
    return Promise.reject(error)
  }
)

// 3. 响应拦截器（统一处理响应、关闭加载状态）
request.interceptors.response.use(
  (response) => {
    if (loadingInstance) loadingInstance.close()
    // 统一处理后端返回格式（code非200视为错误）
    const { code, message, data } = response.data
    if (code !== 200) {
      ElMessage.error(message || '请求失败，请重试')
      return Promise.reject(new Error(message || '请求失败'))
    }
    return data
  },
  (error) => {
    if (loadingInstance) loadingInstance.close()
    // 区分网络错误、后端错误
    const errorMsg = error.response?.data?.message || '服务异常，请检查后端服务是否启动'
    ElMessage.error(errorMsg)
    return Promise.reject(error)
  }
)

// 流式请求核心方法（接收后端SSE数据）
export function streamRequest(url, params) {
  // 拼接GET请求参数
  const paramStr = new URLSearchParams(params).toString()
  const fullUrl = `${baseURL}${url}?${paramStr}`

  // 创建EventSource实例，建立长连接
  const eventSource = new EventSource(fullUrl, { withCredentials: true })

  // 监听连接打开事件
  eventSource.onopen = () => {
    console.log('SSE连接已建立')
  }

  // 监听连接错误事件（处理中断、重连）
  eventSource.onerror = (error) => {
    console.error('SSE连接异常：', error)
    // 连接中断时，尝试重连（最多重连3次）
    if (eventSource.readyState === EventSource.CLOSED) {
      let reconnectCount = 0
      const reconnect = () => {
        if (reconnectCount >= 3) {
          ElMessage.error('流式连接失败，请重试')
          return
        }
        reconnectCount++
        setTimeout(() => {
          streamRequest(url, params) // 重新发起连接
        }, 1000 * reconnectCount)
      }
      reconnect()
    }
  }

  return eventSource
}

// 导出普通请求实例和流式请求方法
export default request