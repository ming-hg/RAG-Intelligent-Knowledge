// rag-server/services/ragService.js
const { RecursiveCharacterTextSplitter } = require('langchain/text_splitter');
const { Chroma } = require('@langchain/community/vectorstores/chroma');
const { generateEmbedding } = require('../utils/lmStudio');
const pdf = require('pdf-parse');
const mammoth = require('mammoth');
const fs = require('fs');
const path = require('path');

// 中文场景优化的文本分块配置（适配中文语义，避免断句、断词）
const textSplitter = new RecursiveCharacterTextSplitter({
  chunkSize: 500, // 单块最大字符数（中文适配，避免块过大导致检索精度下降）
  chunkOverlap: 100, // 块重叠字符数（保留上下文关联，避免语义断裂）
  separators: ['\n\n', '\n', '。', '！', '？', '，', '；', '：', ' ', ''], // 中文优先分隔符
  keepSeparator: false // 不保留分隔符，避免分隔符占用字符数
});

/**
 * 中文语义分块核心方法（单独封装，方便调用）
 * @param {String} rawText - 清洗后的纯文本
 * @returns {Array} 语义分块后的文本数组
 */
async function splitTextByChineseSemantic(rawText) {
  try {
    // 执行分块
    const chunks = await textSplitter.splitText(rawText);
    // 过滤过短分块（小于50字符，避免无效分块）
    const validChunks = chunks.filter(chunk => chunk.length >= 50);
    if (validChunks.length === 0) {
      throw new Error('文档分块后无有效内容，请上传更长的文档');
    }
    return validChunks;
  } catch (error) {
    console.error('中文语义分块失败：', error);
    throw new Error('文本分块失败，请检查文档内容');
  }
}


// 自定义Embedding适配类，对接LM Studio
class CustomEmbeddings {
  // 生成查询向量（用于用户问题向量化）
  async embedQuery(text) {
    return await generateEmbedding(text);
  }
  // 生成文档向量（用于文档分块向量化）
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
    [], // 初始文本为空
    [], // 初始元数据为空
    embeddings,
    {
      collectionName: 'local-rag-knowledge', // 向量库集合名称（固定，不可随意修改）
      url: 'http://localhost:8000', // ChromaDB本地服务地址
      timeout: 30000 // 连接超时时间（30秒）
    }
  );
}

/**
 * 文档分块批量入库（原有addDocumentToKnowledge方法已调用，补充单独入库方法）
 * @param {Array} chunks - 语义分块后的文本数组
 * @param {Object} metadata - 文档元数据（文件名、上传时间等）
 */
async function batchAddChunksToVectorStore(chunks, metadata) {
  const vectorStore = await initVectorStore();
  // 为每个分块添加独立元数据（关联文档信息）
  const documents = chunks.map((chunk, index) => ({
    pageContent: chunk,
    metadata: {
      ...metadata, // 继承文档级元数据
      chunkIndex: index, // 分块序号
      chunkLength: chunk.length, // 分块长度
      content: chunk // 分块内容（便于后续溯源）
    }
  }));
  // 批量入库
  await vectorStore.addDocuments(documents);
  return { chunkCount: chunks.length, success: true };
}

/**
 * 按文件名删除文档（删除该文档对应的所有分块）
 * @param {String} fileName - 要删除的文档名称
 */
async function deleteDocumentByFileName(fileName) {
  const vectorStore = await initVectorStore();
  // 检索该文件对应的所有分块
  const docs = await vectorStore.similaritySearch(`fileName:${fileName}`, 1000); // 批量检索
  if (docs.length === 0) {
    throw new Error(`未找到名称为${fileName}的文档`);
  }
  // 提取所有分块的ID，批量删除
  const docIds = docs.map(doc => doc.id);
  await vectorStore.delete({ ids: docIds });
  return { success: true, message: `文档${fileName}删除成功`, deletedCount: docIds.length };
}

/**
 * 清空整个知识库（谨慎使用）
 */
async function clearKnowledgeBase() {
  const vectorStore = await initVectorStore();
  await vectorStore.delete({ ids: [] }); // 空数组表示删除所有内容
  return { success: true, message: '知识库已清空' };
}

/**
 * 查询知识库列表（获取所有文档的元数据，去重）
 */
async function getKnowledgeBaseList() {
  const vectorStore = await initVectorStore();
  const docs = await vectorStore.similaritySearch('', 1000); // 空查询匹配所有文档
  // 按文件名去重，提取每个文档的核心元数据
  const docMap = new Map();
  docs.forEach(doc => {
    const fileName = doc.metadata.fileName;
    if (!docMap.has(fileName)) {
      docMap.set(fileName, {
        fileName: fileName,
        uploadTime: doc.metadata.uploadTime,
        chunkCount: 1,
        totalLength: doc.metadata.chunkLength
      });
    } else {
      docMap.get(fileName).chunkCount += 1;
      docMap.get(fileName).totalLength += doc.metadata.chunkLength;
    }
  });
  // 转换为数组返回
  return Array.from(docMap.values());
}

/**
 * 解析不同格式文档，提取纯文本内容并完成清洗
 * @param {String} filePath - 临时文件路径
 * @param {String} mimeType - 文件MIME类型
 * @returns {String} 清洗后的纯文本
 */
async function extractTextFromFile(filePath, mimeType) {
  const fileBuffer = fs.readFileSync(filePath);
  let text = '';

  // 分格式提取文本
  switch (mimeType) {
    // PDF提取：使用pdf-parse库，适配复杂布局和多页文档
    case 'application/pdf':
      const pdfData = await pdf(fileBuffer);
      text = pdfData.text;
      break;
    // DOCX提取：使用mammoth库，保留文本内容和基本换行，去除样式和复杂元素
    case 'application/vnd.openxmlformats-officedocument.wordprocessingml.document':
      const docxData = await mammoth.extractRawText({ buffer: fileBuffer });
      text = docxData.value;
      break;
    // 文本文件提取：直接读取文本内容，适配纯文本和Markdown格式
    case 'text/plain':
      text = fileBuffer.toString('utf-8');
      break;
    // Markdown文件提取：读取文本内容，并进行Markdown语法清洗，保留纯文本信息
    case 'text/markdown':
      text = fileBuffer.toString('utf-8');
      // Markdown格式清洗：去除Markdown语法（标题、列表、链接等），保留纯文本
      text = text.replace(/#{1,6}\s+/g, '') // 去除标题
        .replace(/\*\*|\*/g, '') // 去除粗体/斜体
        .replace(/\[\w+\]\(\S+\)/g, '') // 去除链接
        .replace(/[-*+]\s+/g, '') // 去除列表符号
        .replace(/`{1,3}[\s\S]*?`{1,3}/g, ''); // 去除代码块
      break;
    default:
      throw new Error('不支持的文件格式，仅支持PDF/DOCX/MD/TXT格式');
  }

  // 文本深度清洗：去除多余空白字符、无效字符，修复换行异常
  text = text
    .replace(/\s+/g, ' ') // 所有空白字符（空格、换行、制表符）替换为单个空格
    .replace(/[\u0000-\u001F\u007F]/g, '') // 去除不可见控制字符
    .replace(/\s{2,}/g, ' ') // 多个连续空格替换为单个
    .trim(); // 去除首尾空格

  // 过滤无效文本（纯空格、长度过短的文本）
  if (text.length < 10) {
    throw new Error('文档内容为空或有效内容过少，无法解析');
  }

  return text;
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

    // 2. 文本分块：适配中文场景，合理分割文本，保留上下文连续性
    const chunks = await textSplitter.splitText(rawText);

    // 3. 向量化并入库：使用自定义Embedding适配类生成向量，存储到ChromaDB
    const vectorStore = await initVectorStore();
    const documents = chunks.map((chunk, index) => ({
      pageContent: chunk,
      metadata: {
        fileName: originalname,
        chunkIndex: index,
        content: chunk,
        uploadTime: new Date().toISOString(),
      }
    }));
    await vectorStore.addDocuments(documents);

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

module.exports = { 
  initVectorStore, // 导出向量库初始化方法，供外部调用
  addDocumentToKnowledge, // 导出文档入库方法，供路由调用
  batchAddChunksToVectorStore, // 导出批量入库方法，供外部调用
  deleteDocumentByFileName, // 导出按文件名删除方法，供路由调用
  clearKnowledgeBase, // 导出清空知识库方法，供路由调用
  getKnowledgeBaseList, // 导出查询知识库列表方法，供路由调用
  extractTextFromFile, // 导出文档文本提取方法，供外部调用
  splitTextByChineseSemantic, // 导出中文语义分块方法，供外部调用
};