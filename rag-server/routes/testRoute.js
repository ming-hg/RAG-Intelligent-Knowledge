const express = require('express');
const router = express.Router();
// 引入LM Studio对接工具类的两个核心方法
const { generateChatCompletion, generateEmbedding } = require('../utils/lmStudio');

// 测试接口：验证后端与LM Studio的连通性（调用聊天接口测试）
router.post('/test/chat', async (req, res, next) => {
  try {
    const { question } = req.body; // 从请求体中获取用户问题
    if (!question?.trim()) {
      return res.status(400).json({
        code: 400,
        message: '问题不能为空',
        data: null
      });
    }
    const messages = [
        { role: 'system', content: '你是一个知识渊博的助手，帮助用户解答问题。' }, // 系统提示词，设定助手角色和行为
        { role: 'user', content: question.trim() } // 用户问题，去除前后空白字符
    ]; // 构建对话消息列表
    // 调用LM Studio的聊天接口，获取生成结果
    const response = await generateChatCompletion(messages, false); // false表示全量响应
    const answer = response.choices[0].message.content; // 提取生成的回答文本
    // 返回生成结果给前端
    res.json({
        code: 200,
        message: '大模型调用成功',
        data: { question: question.trim(), answer } // 返回原问题和生成的回答
    })
  } catch (error) {
    next(error); // 交给全局异常拦截中间件处理
  }
});

// 测试接口：验证后端与LM Studio的连通性（调用Embedding接口测试）
router.post('/test/embedding', async (req, res, next) => {
  try {
    const { text } = req.body; // 从请求体中获取待向量化文本
    if (!text?.trim()) {
      return res.status(400).json({
        code: 400,
        message: '文本不能为空',
        data: null
      });
    }
    // 调用LM Studio的Embedding接口，获取向量化结果
    const embedding = await generateEmbedding(text);
    // 返回向量结果（仅展示前10个元素，避免返回内容过长）
    res.json({
      code: 200,
      message: 'Embedding调用成功',
      data: { 
        text: text, 
        embedding: embedding.slice(0, 10), // 仅返回前10个元素，实际应用中可返回完整向量数组
        embeddingLength: embedding.length // 返回向量长度，供前端验证维度是否正确   
     }
    });

  } catch (error) {
    next(error); // 交给全局异常拦截中间件处理
  }
});

module.exports = router;
