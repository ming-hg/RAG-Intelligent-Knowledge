// rag-server/services/ragService.js
const { RecursiveCharacterTextSplitter } = require('langchain/text_splitter');
const { Chroma } = require('@langchain/community/vectorstores/chroma');
const { generateEmbedding } = require('../utils/lmStudio');
const pdf = require('pdf-parse');
const mammoth = require('mammoth');
const fs = require('fs');
const path = require('path');

// 中文场景优化的文本分块配置
const textSplitter = new RecursiveCharacterTextSplitter({
  chunkSize: 500,
  chunkOverlap: 100,
  separators: ['\n\n', '\n', '。', '！', '？', '，', ' ', ''],
});

// 自定义Embedding适配类，对接LM Studio
class CustomEmbeddings {
  async embedQuery(text) {
    return await generateEmbedding(text);
  }

  async embedDocuments(texts) {
    const embeddings = [];
    for (const text of texts) {
      const embedding = await generateEmbedding(text);
      embeddings.push(embedding);
    }
    return embeddings;
  }
}

/**
 * 初始化Chroma向量数据库实例
 */
async function initVectorStore() {
  const embeddings = new CustomEmbeddings();
  return await Chroma.fromTexts(
    [],
    [],
    embeddings,
    { 
      collectionName: 'local-rag-knowledge', 
      url: 'http://localhost:8000' // ChromaDB本地服务地址
    }
  );
}

/**
 * 解析不同格式文档，提取纯文本内容
 */
async function extractTextFromFile(filePath, mimeType) {
  const fileBuffer = fs.readFileSync(filePath);
  let text = '';

  switch (mimeType) {
    case 'application/pdf':
      const pdfData = await pdf(fileBuffer);
      text = pdfData.text;
      break;
    case 'application/vnd.openxmlformats-officedocument.wordprocessingml.document':
      const docxData = await mammoth.extractRawText({ buffer: fileBuffer });
      text = docxData.value;
      break;
    case 'text/plain':
    case 'text/markdown':
      text = fileBuffer.toString('utf-8');
      break;
    default:
      throw new Error('不支持的文件格式，仅支持PDF/DOCX/MD/TXT格式');
  }

  // 文本清洗：去除多余空白字符
  return text.replace(/\s+/g, ' ').trim();
}

/**
 * 文档上传并加入知识库核心方法
 */
async function addDocumentToKnowledge(file) {
  const { path: filePath, originalname, mimetype } = file;
  try {
    // 1. 提取文档文本
    const rawText = await extractTextFromFile(filePath, mimetype);
    if (!rawText || rawText.length < 10) {
      throw new Error('文档内容为空或内容过少，无法入库');
    }

    // 2. 文本语义分块
    const chunks = await textSplitter.splitText(rawText);

    // 3. 存入向量数据库
    const vectorStore = await initVectorStore();
    await vectorStore.addTexts(chunks, chunks.map((chunk, index) => ({
      fileName: originalname,
      chunkIndex: index,
      content: chunk,
      uploadTime: new Date().toISOString(),
    })));

    // 删除临时文件
    fs.unlinkSync(filePath);

    return {
      success: true,
      fileName: originalname,
      chunkCount: chunks.length,
      message: '文档上传成功，已加入知识库'
    };
  } catch (error) {
    // 异常情况下删除临时文件
    if (fs.existsSync(filePath)) {
      fs.unlinkSync(filePath);
    }
    console.error('文档入库失败：', error);
    throw error;
  }
}

module.exports = { initVectorStore, addDocumentToKnowledge };