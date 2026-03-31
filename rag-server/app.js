const express = require('express');
const corsMiddleware = require('./config/corsConfig'); // 引入跨域配置
const ragRouter = require('./routes/ragRoute'); // 引入RAG问答路由
const healthRouter = require('./routes/healthRoute'); // 引入健康检查路由
const testRouter = require('./routes/testRoute'); // 引入测试路由
const documentRouter = require('./routes/documentRoute'); // 引入文档上传路由
const sessionRouter = require('./routes/sessionRoute'); // 引入会话管理路由
const path = require('path'); // 引入路径模块，配置静态文件目录

// 1. 初始化Express实例
const app = express();
// 2. 配置后端服务端口（默认3000，与前端axios基础地址一致，不可随意修改）
const port = 3000;

// 3. 核心中间件配置（顺序不可乱，跨域在前，解析在后）
// 3.1 启用跨域中间件，解决前后端跨域请求问题
app.use(corsMiddleware);
// 3.2 解析JSON格式请求体（接收前端传递的JSON参数，如问答接口的question）
app.use(express.json({ limit: '10mb' })); // 限制请求体大小，适配大文件上传相关接口
// 3.3 解析URL-encoded格式请求体（适配表单提交、文件上传等场景）
app.use(express.urlencoded({ extended: true, limit: '10mb' }));
// 3.4 配置静态文件服务（后续前端打包后，后端可直接访问前端静态资源）
app.use(express.static(path.join(__dirname, 'public')));

// 接口耗时统计中间件（所有接口通用）
app.use((req, res, next) => {
  req._startTime = Date.now(); // 记录请求开始时间
  next();
});

// 引入健康检查路由（可放在跨域配置之后、业务路由之前）
app.use('/api', healthRouter);

// 引入测试路由（可选，便于后续功能开发测试）
app.use('/api', testRouter);

// 引入会话路由（与其他路由同级）
app.use('/api', sessionRouter);

// 引入文档上传路由（放在健康检查和测试路由之后，业务路由之前）
app.use('/api', documentRouter)

// 引入RAG问答路由（放在健康检查路由之后即可）
app.use('/api', ragRouter);

// 全局异常拦截中间件（捕获所有接口异常，统一返回错误格式，避免服务崩溃）
app.use((err, req, res, next) => {
  console.error('全局异常：', err);
  res.status(500).json({
    code: 500,
    message: err.message || '后端服务异常，请稍后重试',
    data: null
  });
});

// 启动后端服务
app.listen(port, () => {
  console.log(`Express基础服务启动成功，地址：http://localhost:${port}`);
});

module.exports = app; // 导出app实例，方便后续测试和PM2部署