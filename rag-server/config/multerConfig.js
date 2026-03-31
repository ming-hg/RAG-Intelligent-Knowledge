const multer = require('multer');
const path = require('path');
const fs = require('fs');

// 定义临时文件存储路径（项目根目录下的temp文件夹）
const tempDir = path.join(__dirname, '../temp');
if (!fs.existsSync(tempDir)) {
  fs.mkdirSync(tempDir, { recursive: true }); // 不存在则创建
}

// 配置文件格式白名单（仅允许PDF/DOCX/MD/TXT）
const allowedFileTypes = [
  'application/pdf',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'text/markdown',
  'text/plain'
];

// 配置multer
const storage = multer.diskStorage({
  destination: (req, file, cb) => {
    cb(null, tempDir); // 临时文件存储路径
  },
  filename: (req, file, cb) => {
    // 自定义文件名：时间戳+原始文件名，避免重复
    const uniqueName = `${Date.now()}-${file.originalname}`;
    cb(null, uniqueName);
  }
});

// 文件过滤：校验格式和大小（单文件不超过100MB）
const fileFilter = (req, file, cb) => {
  if (allowedFileTypes.includes(file.mimetype)) {
    if (file.size > 100 * 1024 * 1024) { // 100MB限制
      return cb(new Error('单文件大小不能超过100MB'), false);
    }
    cb(null, true);
  } else {
    cb(new Error('不支持的文件格式，仅支持PDF/DOCX/MD/TXT格式'), false);
  }
};

// 导出multer实例（支持多文件上传，最多同时上传5个）
const upload = multer({
  storage: storage,
  fileFilter: fileFilter,
  limits: { fileSize: 100 * 1024 * 1024 } // 单个文件大小限制，与fileFilter一致
});

module.exports = upload;