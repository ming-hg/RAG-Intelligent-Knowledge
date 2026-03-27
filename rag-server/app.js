const express = require('express');
const corsMiddleware = require('./config/corsConfig'); // 引入跨域配置
const ragRouter = require('./routes/ragRoute'); // 引入RAG问答路由
const app = express();
const port = 3000; // 后端默认端口，与前端axios配置对应

// 第一步：启用跨域中间件（必须放在所有接口之前）
app.use(corsMiddleware);

// 后续可继续添加JSON解析、路由等配置
app.use(express.json()); // 解析JSON格式请求体
// ... 其他代码（路由、服务启动等）

// 引入RAG问答路由（放在健康检查路由之后即可）
app.use('/api', ragRouter);