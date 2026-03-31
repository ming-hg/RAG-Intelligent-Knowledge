// rag-server/controllers/ragController.js
const { generateChatCompletion } = require('../utils/lmStudio');
const { RAG_SYSTEM_PROMPT, PROMPT_CONFIG } = require('../constants/prompt');
const { initVectorStore } = require('../services/ragService');
const { v4: uuidv4 } = require('uuid'); // 用于生成会话ID
const { addSessionMessage, createSession } = require('../services/sessionService');

/**
 * 全量问答接口
 * @param {Object} req - 请求对象，包含question（必传）、sessionId（可选）
 * @param {Object} res - 响应对象
 */
async function ragChat(req, res) {
  try {
    const { question, sessionId } = req.body;
    // 1. 参数校验（补充细节：严格校验问题格式，过滤空字符串、纯空格）
    if (!question?.trim() || question.length < 2) {
      return res.status(400).json({ code: 400, message: '问题内容不能为空，且长度必须在2个字符以上' });
    }

    // 2. 处理会话ID（补充细节：若前端未传，自动生成唯一会话ID，用于后续会话管理）
    const currentSessionId = sessionId || uuidv4();

    // 3. 初始化向量库，执行相似度检索（补充细节：使用配置中的检索条数，增加检索超时处理）
    const vectorStore = await initVectorStore();
    // 检索超时控制（避免检索耗时过长导致接口超时）
    const retrieveResult = await Promise.race([
      vectorStore.similaritySearch(question, PROMPT_CONFIG.retrieveTopK),
      new Promise((_, reject) => setTimeout(() => reject(new Error('检索超时，请重试')), 10000))
    ]);

    // 4. 处理检索空结果（补充细节：若未检索到相关内容，直接返回对应提示，不调用大模型）
    if (retrieveResult.length === 0) {
      return res.json({
        code: 200,
        data: {
          answer: '抱歉，当前知识库中没有找到相关内容，请补充对应文档后再提问。',
          sourceList: [], // 空来源列表
          sessionId: currentSessionId
        },
        message: 'success'
      });
    }

    // 5. 拼接检索到的上下文（补充细节：去重处理，避免重复内容影响回答）
    const contextSet = new Set();
    retrieveResult.forEach(item => contextSet.add(item.pageContent));
    const context = Array.from(contextSet).join('\n\n'); // 去重后拼接
    // 组装引用来源（补充细节：格式化来源信息，方便前端展示）
    const sourceList = retrieveResult.map(item => ({
      fileName: item.metadata.fileName || '未知文档',
      chunkIndex: item.metadata.chunkIndex + 1, // 分块索引从1开始，更易读
      uploadTime: new Date(item.metadata.uploadTime).toLocaleString(), // 格式化时间
      content: item.pageContent.slice(0, 50) + '...' // 截取前50字符，避免来源过长
    }));

    // 6. 组装Prompt（补充细节：追加强制提示，双重控制幻觉）
    const messages = [
      {
        role: 'system',
        content: RAG_SYSTEM_PROMPT.replace('{{context}}', context)
      },
      {
        role: 'user',
        content: `${question} ${PROMPT_CONFIG.forcePrompt}` // 追加强制提示
      }
    ];

    // 7. 调用大模型生成回答（补充细节：优化模型参数，增加超时控制）
    const response = await Promise.race([
      generateChatCompletion(messages, false),
      new Promise((_, reject) => setTimeout(() => reject(new Error('大模型调用超时，请重试')), 30000))
    ]);
    const answer = response.choices[0].message.content;

    // 新增：存储会话消息（用户消息+AI消息）
    try {
      // 先尝试添加消息，若会话不存在，自动创建新会话
      await addSessionMessage(currentSessionId, {
        role: 'user',
        content: question
      });
      await addSessionMessage(currentSessionId, {
        role: 'assistant',
        content: answer
      });
    } catch (error) {
      // 会话不存在，创建新会话并存储消息
      const newSession = createSession();
      await addSessionMessage(newSession.sessionId, {
        role: 'user',
        content: question
      });
      await addSessionMessage(newSession.sessionId, {
        role: 'assistant',
        content: answer
      });
      // 更新当前会话ID为新创建的会话ID
      currentSessionId = newSession.sessionId;
    }

    // 8. 返回结果（补充细节：格式化响应，增加接口耗时统计）
    const responseTime = Date.now() - req._startTime; // 需在app.js中添加req._startTime（见步骤3）
    return res.json({
      code: 200,
      data: {
        answer,
        sourceList,
        sessionId: currentSessionId,
        responseTime: `${responseTime}ms` // 接口耗时，方便前端优化
      },
      message: 'success'
    });
  } catch (error) {
    console.error('问答接口异常：', error);
    // 补充细节：分类返回错误信息，方便前端定位问题
    if (error.message.includes('检索超时')) {
      return res.status(504).json({ code: 504, message: error.message });
    }
    if (error.message.includes('大模型调用超时')) {
      return res.status(504).json({ code: 504, message: error.message });
    }
    return res.status(500).json({ code: 500, message: error.message || '服务异常，请稍后重试' });
  }
}

/**
 * 流式问答接口（SSE）- 完整实现，支持中断重连、异常处理
 * 核心逻辑：SSE长连接 → 问题检索 → Prompt组装 → 流式调用大模型 → 实时推送Token → 连接管理
 */
async function ragChatStream(req, res) {
  try {
    const { question, sessionId } = req.query;
    // 1. 参数校验（补充：GET请求参数校验，与全量接口保持一致）
    if (!question?.trim() || question.length < 2) {
      return res.status(400).send('data: ' + JSON.stringify({
        error: '问题内容不能为空，且长度至少2个字符'
      }) + '\n\n');
    }

    // 2. 处理会话ID（与全量接口逻辑一致）
    const currentSessionId = sessionId || uuidv4();

    // 3. 设置SSE响应头（补充细节：完善跨域、缓存、连接保持配置）
    res.setHeader('Content-Type', 'text/event-stream');
    res.setHeader('Cache-Control', 'no-cache, no-store, must-revalidate');
    res.setHeader('Connection', 'keep-alive');
    res.setHeader('Access-Control-Allow-Origin', 'http://localhost:5173'); // 明确前端地址，避免跨域问题
    res.setHeader('Access-Control-Allow-Credentials', 'true');
    res.flushHeaders(); // 强制刷新响应头，避免浏览器缓存

    // 4. 相似度检索（与全量接口一致，增加超时处理）
    const vectorStore = await initVectorStore();
    const retrieveResult = await Promise.race([
      vectorStore.similaritySearch(question, PROMPT_CONFIG.retrieveTopK),
      new Promise((_, reject) => setTimeout(() => reject(new Error('检索超时，请重试')), 10000))
    ]);

    // 5. 处理检索空结果（流式返回提示，不调用大模型）
    if (retrieveResult.length === 0) {
      res.write(`data: ${JSON.stringify({
        content: '抱歉，当前知识库中没有找到相关内容，请补充对应文档后再提问。',
        sessionId: currentSessionId,
        done: false
      })}\n\n`);
      // 发送结束标记
      res.write(`data: ${JSON.stringify({ done: true })}\n\n`);
      return res.end();
    }

    // 6. 拼接上下文与组装Prompt（与全量接口一致）
    const contextSet = new Set();
    retrieveResult.forEach(item => contextSet.add(item.pageContent));
    const context = Array.from(contextSet).join('\n\n');
    const sourceList = retrieveResult.map(item => ({
      fileName: item.metadata.fileName || '未知文档',
      chunkIndex: item.metadata.chunkIndex + 1,
      uploadTime: new Date(item.metadata.uploadTime).toLocaleString(),
      content: item.pageContent.slice(0, 50) + '...'
    }));
    const messages = [
      { role: 'system', content: RAG_SYSTEM_PROMPT.replace('{{context}}', context) },
      { role: 'user', content: `${question} ${PROMPT_CONFIG.forcePrompt}` }
    ];

    // 7. 处理连接中断（补充边界处理：客户端关闭连接时，终止流式推送，释放资源）
    req.on('close', () => {
      console.log(`客户端关闭连接，会话ID：${currentSessionId}`);
      res.end(); // 关闭响应流
    });

    // 8. 调用LM Studio流式接口，实时推送Token（补充细节：处理流式中断、异常）
    const stream = await Promise.race([
      generateChatCompletion(messages, true),
      new Promise((_, reject) => setTimeout(() => reject(new Error('大模型调用超时，请重试')), 30000))
    ]);

    // 9. 逐块推送Token（补充细节：处理空Token、格式校验）
    for await (const chunk of stream) {
      const content = chunk.choices[0]?.delta?.content || '';
      if (content) {
        // 格式化推送数据，确保前端能正常解析
        const pushData = JSON.stringify({
          content,
          sessionId: currentSessionId,
          done: false
        });
        res.write(`data: ${pushData}\n\n`);
        // 控制推送速度，避免前端渲染卡顿（可选，根据模型响应速度调整）
        await new Promise(resolve => setTimeout(resolve, 30));
      }
    }

    // 10. 流式结束标记（补充：推送来源列表，与全量接口一致）
    res.write(`data: ${JSON.stringify({
      sourceList,
      done: true
    })}\n\n`);
    res.end(); // 正常结束响应流

    // 流式结束后，存储会话消息
    try {
      await addSessionMessage(currentSessionId, {
        role: 'user',
        content: question
      });
      await addSessionMessage(currentSessionId, {
        role: 'assistant',
        content: answer // 需在流式推送时拼接完整answer，存储完整消息
      });
    } catch (error) {
      const newSession = createSession();
      await addSessionMessage(newSession.sessionId, {
        role: 'user',
        content: question
      });
      await addSessionMessage(newSession.sessionId, {
        role: 'assistant',
        content: answer
      });
      currentSessionId = newSession.sessionId;
    }

  } catch (error) {
    console.error('流式问答接口异常：', error);
    // 补充：异常流式返回，让前端能捕获错误并提示
    const errorMsg = error.message || '流式问答服务异常，请稍后重试';
    res.write(`data: ${JSON.stringify({ error: errorMsg, done: true })}\n\n`);
    res.end();
  }
}

module.exports = { ragChat, ragChatStream };