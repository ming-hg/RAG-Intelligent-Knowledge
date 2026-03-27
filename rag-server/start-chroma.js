// chroma-start.js
const { ChromaServer } = require('chromadb');

// 启动 Chroma 服务，数据存储在 ./chroma-data
const server = new ChromaServer({
  persistDirectory: './chroma-data', // 数据存放路径（对应你原来的 --path）
  port: 8000, // 默认端口
  host: 'localhost'
});

// 启动服务
server.start().then(() => {
  console.log('✅ Chroma 服务启动成功！');
  console.log('📦 数据路径：./chroma-data');
  console.log('🌐 访问地址：http://localhost:8000');
}).catch(err => {
  console.error('❌ 启动失败：', err);
});