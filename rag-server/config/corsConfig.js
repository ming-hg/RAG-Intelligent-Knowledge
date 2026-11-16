// 跨域配置文件，允许前端地址访问，适配本地开发环境
const cors = require('cors');

// 配置允许跨域的前端地址（默认前端地址http://localhost:5173）
const corsOptions = {
  origin: 'http://localhost:5173', // 前端服务地址，不可随意修改
  methods: ['GET', 'POST', 'PUT', 'DELETE'], // 允许的请求方式
  allowedHeaders: ['Content-Type', 'Authorization'], // 允许的请求头
  credentials: true, // 允许携带cookie（可选，本项目可保留默认true）
  maxAge: 86400 // 预检请求缓存时间（24小时）
};
module.exports = cors(corsOptions);// rag-server/app.js（入口文件，引入跨域配置）