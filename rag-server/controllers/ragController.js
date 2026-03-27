// rag-server/controllers/ragController.js
const { generateChatCompletion } = require('../utils/lmStudio');
const { RAG_SYSTEM_PROMPT } = require('../constants/prompt');
const { initVectorStore } = require('../services/ragService');

/**
 * 全量问答接口
 */
async function ragChat(req, res) {
  try {
    const { question, sessionId } = req.body;
    if (!question?.trim()) {
      return res.status(400).json({ code: 400, message: '问题内容不能为空' });
    }

    // 1. 初始化向量库，执行相似度检索
    const vectorStore = await initVectorStore();
    const retrieveResult = await vectorStore.similaritySearch(question, 4); // 召回Top4相关文档块

    // 2. 拼接检索到的上下文
    const context = retrieveResult.map(item => item.pageContent).join('\n\n');
    const sourceList = retrieveResult.map(item => item.metadata);

    // 3. 组装Prompt
    const messages = [
      {
        role: 'system',
        content: RAG_SYSTEM_PROMPT.replace('{{context}}', context)
      },
      {
        role: 'user',
        content: question
      }
    ];

    // 4. 调用大模型生成回答
    const response = await generateChatCompletion(messages, false);
    const answer = response.choices[0].message.content;

    // 5. 返回结果
    return res.json({
      code: 200,
      data: {
        answer,
        sourceList,
        sessionId
      },
      message: 'success'
    });
  } catch (error) {
    console.error('问答接口异常：', error);
    return res.status(500).json({ code: 500, message: error.message || '服务异常' });
  }
}

/**
 * 流式问答接口（SSE）
 */
async function ragChatStream(req, res) {
  try {
    const { question, sessionId } = req.query;
    if (!question?.trim()) {
      return res.status(400).send('问题内容不能为空');
    }

    // 设置SSE响应头
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache');
    res.setHeader('Connection', 'keep-alive');
    res.setHeader('Access-Control-Allow-Origin', '*');

    // 1. 相似度检索
    const vectorStore = await initVectorStore();
    const retrieveResult = await vectorStore.similaritySearch(question, 4);
    const context = retrieveResult.map(item => item.pageContent).join('\n\n');

    // 2. 组装Prompt
    const messages = [
      { role: 'system', content: RAG_SYSTEM_PROMPT.replace('{{context}}', context) },
      { role: 'user', content: question }
    ];

    // 3. 调用流式接口，实时推送数据
    const stream = await generateChatCompletion(messages, true);
    for await (const chunk of stream) {
      const content = chunk.choices[0]?.delta?.content || '';
      if (content) {
        res.write(`data: ${JSON.stringify({ content, sessionId })}\n\n`);
      }
    }

    // 流式结束标记
    res.write(`data: ${JSON.stringify({ done: true })}\n\n`);
    res.end();
  } catch (error) {
    console.error('流式问答接口异常：', error);
    res.write(`data: ${JSON.stringify({ error: error.message })}\n\n`);
    res.end();
  }
}

module.exports = { ragChat, ragChatStream };