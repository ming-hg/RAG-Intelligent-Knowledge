// rag-server/routes/ragRoute.js
// RAG问答核心路由，包含全量问答、流式问答接口
const express = require('express');
const router = express.Router();
const { ragChat, ragChatStream } = require('../controllers/ragController'); // 引入已有的控制器方法

// 补充：路由参数校验中间件（简单校验，避免无效请求）
const validateChatParams = (req, res, next) => {
  const { question } = req.body;
  if (!question?.trim()) {
    return res.status(400).json({ code: 400, message: '问题内容不能为空' });
  }
  next();
};

// 全量问答接口（POST请求，对应前端调用的/api/rag/chat）
// 补充：添加参数校验中间件，提前拦截无效请求
router.post('/rag/chat', ragChat);

// 流式问答接口（GET请求，用于SSE流式响应，可选补充）
router.get('/rag/chat/stream', ragChatStream);

module.exports = router;