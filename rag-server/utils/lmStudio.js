// rag-server/utils/lmStudio.js（LM Studio对接工具类，完整实现）
const OpenAI = require('openai'); // 引入openai包（4.1.2节已安装，版本4.52.7）

// 1. 初始化OpenAI客户端，对接LM Studio本地服务（核心配置）
const client = new OpenAI({
  baseURL: 'http://localhost:1234/v1', // LM Studio本地服务默认地址，不可修改（需与LM Studio启动的地址一致）
  apiKey: 'lm-studio', // 本地服务无需真实API Key，任意非空字符串即可（固定填写lm-studio即可）
});

/**
 * 2. 调用大模型生成对话内容（支持全量响应/流式响应，适配后续问答接口）
 * @param {Array} messages - 对话消息列表（格式：[{role: 'system', content: '提示词'}, {role: 'user', content: '问题'}]）
 * @param {Boolean} stream - 是否开启流式响应（true：流式，false：全量）
 * @returns {Object} - 大模型生成结果（全量：完整回答；流式：可迭代的Token流）
 */
async function generateChatCompletion(messages, stream = false) {
  try {
    // 调用LM Studio的聊天接口，参数与OpenAI API完全兼容
    const response = await client.chat.completions.create({
      model: 'qwen2.5-vl-7b-instruct', // 必须与LM Studio中加载的主干模型名称完全一致（如Llama3-8B-Chinese-Chat-GGUF）
      messages: messages, // 对话消息（包含系统提示词和用户问题）
      temperature: 0.3, // RAG场景推荐低温（0.1-0.4），降低大模型幻觉，确保回答基于知识库
      top_p: 0.8, // 控制生成内容的多样性，0.8为最优适配值
      stream: stream, // 是否开启流式响应
    });
    return response; // 返回生成结果，供后续接口使用
  } catch (error) {
    console.error('LM Studio大模型调用失败：', error);
    // 抛出具体错误信息，方便上层接口捕获并返回给前端
    throw new Error('大模型服务调用失败，请检查LM Studio是否正常启动、模型是否正确加载');
  }
}

/**
 * 3. 生成文本的Embedding向量（用于文档分块向量化、问题向量化，支撑RAG检索）
 * @param {String} text - 待向量化的文本（文档分块、用户问题）
 * @returns {Array} - 文本对应的Embedding向量数组（固定维度，由Embedding模型决定）
 */
async function generateEmbedding(text) {
  try {
    // 调用LM Studio的Embedding接口，生成文本向量
    const response = await client.embeddings.create({
      model: 'text-embedding-bge-base-zh-v1.5', // 必须与LM Studio中加载的Embedding模型名称完全一致
      input: text.trim(), // 去除文本前后空白字符，避免无效向量生成
    });
    // 返回向量数组（取第一个结果，因为每次只向量化一段文本）
    return response.data[0].embedding;
  } catch (error) {
    console.error('Embedding生成失败：', error);
    throw new Error('文本向量化失败，请检查LM Studio的Embedding模型是否加载成功');
  }
}

// 导出两个核心方法，供其他模块（ragService、ragController）调用
module.exports = { generateChatCompletion, generateEmbedding };