// rag-server/routes/ragRoute.js
// RAG问答核心路由，包含全量问答、流式问答接口
const express = require('express');
const router = express.Router();
const { ragChat, ragChatStream } = require('../controllers/ragController'); // 引入已有的控制器方法

// 全量问答接口（POST请求，对应前端调用的/api/rag/chat）
router.post('/rag/chat', ragChat);

// 流式问答接口（GET请求，用于SSE流式响应，可选补充）
router.get('/rag/chat/stream', ragChatStream);

module.exports = router;