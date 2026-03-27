// rag-server/utils/lmStudio.js
const OpenAI = require('openai');

// 初始化OpenAI客户端，对接LM Studio本地服务
const client = new OpenAI({
  baseURL: 'http://localhost:1234/v1', // LM Studio本地服务默认地址
  apiKey: 'lm-studio', // 本地服务无需真实API Key，任意非空值即可
});

/**
 * 调用大模型生成对话内容
 * @param {Array} messages 对话消息列表
 * @param {Boolean} stream 是否开启流式响应
 * @returns 大模型生成结果
 */
async function generateChatCompletion(messages, stream = false) {
  try {
    const response = await client.chat.completions.create({
      model: 'qwen2-7b-instruct', // 与LM Studio中加载的模型名称完全一致
      messages: messages,
      temperature: 0.3, // RAG场景推荐低温，降低幻觉
      top_p: 0.8,
      stream: stream,
    });
    return response;
  } catch (error) {
    console.error('LM Studio调用失败：', error);
    throw new Error('大模型服务调用失败，请检查LM Studio是否正常启动');
  }
}

/**
 * 生成文本的Embedding向量
 * @param {String} text 待向量化的文本
 * @returns 向量数组
 */
async function generateEmbedding(text) {
  try {
    const response = await client.embeddings.create({
      model: 'bge-base-zh-v1.5', // 与LM Studio中加载的Embedding模型名称一致
      input: text.trim(),
    });
    return response.data[0].embedding;
  } catch (error) {
    console.error('Embedding生成失败：', error);
    throw new Error('文本向量化失败');
  }
}

module.exports = { generateChatCompletion, generateEmbedding };