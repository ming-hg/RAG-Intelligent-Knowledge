const express = require('express');
const router = express.Router();
const {
  getSessionList,
  createSession,
  getSessionDetail,
  addSessionMessage,
  deleteSession
} = require('../services/sessionService');

// 1. 查询会话列表（GET请求）
router.get('/session/list', (req, res, next) => {
  try {
    const sessionList = getSessionList();
    res.json({
      code: 200,
      data: sessionList,
      message: 'success'
    });
  } catch (error) {
    next(error);
  }
});

// 2. 创建新会话（POST请求）
router.post('/session/create', (req, res, next) => {
  try {
    const { sessionName } = req.body;
    const newSession = createSession(sessionName);
    res.json({
      code: 200,
      data: newSession,
      message: '会话创建成功'
    });
  } catch (error) {
    next(error);
  }
});

// 3. 查询会话详情（GET请求，含历史消息）
router.get('/session/detail/:sessionId', (req, res, next) => {
  try {
    const { sessionId } = req.params;
    const sessionDetail = getSessionDetail(sessionId);
    res.json({
      code: 200,
      data: sessionDetail,
      message: 'success'
    });
  } catch (error) {
    next(error);
  }
});

// 4. 删除会话（DELETE请求）
router.delete('/session/delete/:sessionId', (req, res, next) => {
  try {
    const { sessionId } = req.params;
    const result = deleteSession(sessionId);
    res.json({
      code: 200,
      data: result,
      message: '会话删除成功'
    });
  } catch (error) {
    next(error);
  }
});

module.exports = router;