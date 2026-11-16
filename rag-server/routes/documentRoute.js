const express = require('express');
const router = express.Router();
const upload = require('../config/multerConfig');
const {
  addDocumentToKnowledge,
  deleteDocumentByFileName,
  getKnowledgeBaseList
} = require('../services/ragService');

// 多文件上传接口（字段名file，对应前端上传组件的name）
router.post('/knowledge/upload', upload.array('file', 5), async (req, res, next) => {
    try {
        const files = req.files; // 上传的文件列表
        if (!files || files.length === 0) {
            return res.status(400).json({ error: '请选择要上传的文档' });
        }
        // 处理每个上传的文件，加入知识库
        const resultList = [];
        for (const file of files) {
            const result = await addDocumentToKnowledge(file);
            resultList.push(result);
        }
        res.json({
            code: 200,
            message: '文档上传完成，已加入知识库',
            data: resultList
        });
    } catch (error) {
        next(error); // 交由全局错误处理中间件处理
    }
})

// 2. 文档删除接口（按文件名删除）
router.post('/knowledge/delete', async (req, res, next) => {
  try {
    const { fileName } = req.body;
    if (!fileName) {
      return res.status(400).json({ code: 400, message: '请传入要删除的文档名称' });
    }
    const result = await deleteDocumentByFileName(fileName);
    res.json({ code: 200, message: result.message, data: { deletedCount: result.deletedCount } });
  } catch (error) {
    next(error);
  }
});

// 3. 批量删除接口
router.post('/knowledge/batchDelete', async (req, res, next) => {
  try {
    const { fileNames } = req.body;
    if (!fileNames || !Array.isArray(fileNames) || fileNames.length === 0) {
      return res.status(400).json({ code: 400, message: '请传入要删除的文档名称列表' });
    }
    const results = [];
    for (const fileName of fileNames) {
      const result = await deleteDocumentByFileName(fileName);
      results.push(result);
    }
    res.json({ 
      code: 200, 
      message: '批量删除完成', 
      data: { 
        deletedCount: results.length,
        results: results
      } 
    });
  } catch (error) {
    next(error);
  }
});

// 4. 知识库列表查询接口（获取所有文档信息）
router.get('/knowledge/list', async (req, res, next) => {
  try {
    const docList = await getKnowledgeBaseList();
    res.json({
      code: 200,
      message: '查询成功',
      data: {
        docList: docList,
        totalDoc: docList.length,
        totalChunk: docList.reduce((sum, doc) => sum + doc.chunkCount, 0)
      }
    });
  } catch (error) {
    next(error);
  }
});

// 5. 知识库状态查询接口（获取知识库统计信息）
router.get('/knowledge/status', async (req, res, next) => {
  try {
    const docList = await getKnowledgeBaseList();
    const totalDoc = docList.length;
    const totalChunk = docList.reduce((sum, doc) => sum + doc.chunkCount, 0);
    const totalLength = docList.reduce((sum, doc) => sum + doc.totalLength, 0);
    
    res.json({
      code: 200,
      message: '查询成功',
      data: {
        totalDoc: totalDoc, // 文档总数
        totalChunk: totalChunk, // 分块总数
        totalLength: totalLength, // 总文本长度（字符数）
        updateTime: new Date().toISOString() // 最后查询时间
      }
    });
  } catch (error) {
    next(error);
  }
});

module.exports = router;
