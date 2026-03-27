// 前端axios封装，统一配置基础地址、请求拦截、响应拦截
import axios from 'axios';

// 创建axios实例，配置后端基础地址（默认后端地址http://localhost:3000）
const request = axios.create({
  baseURL: 'http://localhost:3000', // 后端服务地址，与后端端口一致
  timeout: 10000, // 请求超时时间（10秒）
  headers: {
    'Content-Type': 'application/json' // 默认请求头
  }
});

// 请求拦截器（可选，可添加token等请求头）
request.interceptors.request.use(
  (config) => {
    // 后续若添加用户认证，可在此处添加token
    // config.headers.Authorization = `Bearer ${localStorage.getItem('token')}`;
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// 响应拦截器（统一处理错误）
request.interceptors.response.use(
  (response) => {
    // 只返回响应体中的data部分，简化前端调用
    return response.data;
  },
  (error) => {
    // 统一提示错误信息
    ElMessage.error(error.response?.data?.message || '请求失败，请重试');
    return Promise.reject(error);
  }
);

// 导出封装好的axios实例
export default request;