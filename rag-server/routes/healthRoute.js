// 健康检查路由
const express = require('express');
const router = express.Router();
const { generateEmbedding } = require('../utils/lmStudio'); // 引入LM Studio工具类
const { initVectorStore } = require('../services/ragService'); // 引入ChromaDB工具类

// 健康检查主接口（一次验证所有链路）
router.get('/health', async (req, res) => {
  try {
    // 1. 验证后端服务自身正常
    const backendStatus = '正常';

    // 2. 验证后端与LM Studio连通性（调用Embedding接口测试）
    let lmStudioStatus = '异常';
    await generateEmbedding('健康检查测试'); // 调用LM Studio的Embedding接口
    lmStudioStatus = '正常';

    // 3. 验证后端与ChromaDB连通性（初始化向量库测试）
    let chromaStatus = '异常';
    await initVectorStore(); // 初始化ChromaDB连接
    chromaStatus = '正常';

    // 返回所有链路状态
    return res.json({
      code: 200,
      message: '健康检查通过',
      data: {
        backend: backendStatus,
        lmStudio: lmStudioStatus,
        chromaDB: chromaStatus
      }
    });
  } catch (error) {
    // 若某条链路失败，返回具体错误信息
    return res.status(500).json({
      code: 500,
      message: '健康检查失败',
      data: {
        backend: '正常',
        lmStudio: error.message.includes('向量化失败') ? '异常（LM Studio未启动或模型错误）' : '正常',
        chromaDB: error.message.includes('Chroma') ? '异常（ChromaDB未启动）' : '正常'
      }
    });
  }
});

module.exports = router