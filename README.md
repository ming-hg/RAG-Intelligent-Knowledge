# RAG-Intelligent-Knowledge
本项目是一套前后端分离、全本地私有化部署的RAG（检索增强生成）智能知识库问答系统。 系统对接LM Studio本地部署的开源大模型，支持用户上传私有文档（PDF/DOCX/Markdown/TXT等），自动完成文档解析、语义分块、向量化存储与检索，实现基于私有文档的精准问答，全程数据不离开本地设备，彻底解决大模型知识滞后、幻觉严重、敏感数据无法上云的核心痛点。

## 技术栈

### 后端技术
- **Node.js** + **Express.js**：构建RESTful API服务
- **ChromaDB**：本地向量数据库，用于存储文档向量
- **LangChain**：提供文本分块、向量化等RAG核心功能
- **LM Studio**：本地部署的开源大模型，提供生成能力
- **Multer**：处理文件上传
- **pdf-parse**：解析PDF文档
- **mammoth**：解析DOCX文档

### 前端技术
- **Vue 3**：前端框架
- **Element Plus**：UI组件库
- **Pinia**：状态管理
- **Vue Router**：路由管理
- **Markdown-it**：Markdown渲染
- **Highlight.js**：代码高亮
- **Axios**：HTTP请求

## 核心功能

1. **文档管理**
   - 支持上传PDF、DOCX、Markdown、TXT等格式文档
   - 自动解析文档内容，提取纯文本
   - 基于中文语义的智能分块
   - 向量化存储到本地ChromaDB
   - 支持查看和删除已上传文档

2. **智能问答**
   - 基于上传文档的精准问答
   - 流式响应，实现打字机效果
   - Markdown格式回答，支持代码高亮
   - 显示引用来源，提高回答可信度

3. **会话管理**
   - 支持创建多个会话
   - 会话名称自定义
   - 会话历史记录保存
   - 支持清空和删除会话

4. **全本地部署**
   - 所有数据存储在本地，保障数据安全
   - 无需联网，离线使用
   - 对接本地LM Studio大模型

## 项目结构

```
RAG-Intelligent-Knowledge/
├── rag-server/           # 后端服务
│   ├── chroma-data/      # ChromaDB数据存储
│   ├── config/           # 配置文件
│   ├── constants/        # 常量定义
│   ├── controllers/      # 控制器
│   ├── data/             # 数据文件
│   ├── routes/           # 路由
│   ├── services/         # 业务逻辑
│   ├── utils/            # 工具函数
│   ├── app.js            # 主应用
│   └── package.json      # 后端依赖
├── rag-web/              # 前端应用
│   ├── public/           # 静态资源
│   ├── src/              # 源代码
│   │   ├── assets/       # 资源文件
│   │   ├── components/   # 组件
│   │   ├── layout/       # 布局
│   │   ├── router/       # 路由
│   │   ├── store/        # 状态管理
│   │   ├── utils/        # 工具函数
│   │   ├── views/        # 页面
│   │   ├── App.vue       # 根组件
│   │   └── main.js       # 入口文件
│   └── package.json      # 前端依赖
└── README.md             # 项目说明
```

## 安装与使用

### 前置条件
- Node.js 16+ 环境
- LM Studio 本地部署（已加载大模型）
- ChromaDB 本地服务

### 后端安装
1. 进入后端目录：
   ```bash
   cd rag-server
   ```

2. 安装依赖：
   ```bash
   npm install
   ```

3. 启动ChromaDB服务：
   ```bash
   npm run chroma
   ```

4. 启动后端服务：
   ```bash
   npm start
   ```
   后端服务将在 http://localhost:3000 运行

### 前端安装
1. 进入前端目录：
   ```bash
   cd rag-web
   ```

2. 安装依赖：
   ```bash
   npm install
   ```

3. 启动开发服务器：
   ```bash
   npm run dev
   ```
   前端应用将在 http://localhost:5173 运行

### 使用流程
1. 打开前端应用
2. 上传文档到知识库
3. 输入问题进行问答
4. 查看回答和引用来源
5. 管理会话和文档

## 系统架构

1. **数据流程**
   - 文档上传 → 文档解析 → 文本分块 → 向量化 → 存储到ChromaDB
   - 用户提问 → 问题向量化 → 相似文档检索 → 大模型生成 → 流式返回

2. **关键模块**
   - **文档处理**：支持多种格式文档解析，提取纯文本
   - **文本分块**：基于中文语义的智能分块，保留上下文
   - **向量化**：使用LM Studio生成向量
   - **检索**：基于相似度的文档检索
   - **生成**：结合检索结果和大模型生成回答

## 注意事项

1. **性能优化**
   - 大型文档可能需要较长时间处理
   - 建议将大型文档拆分为多个小文档上传

2. **安全考虑**
   - 所有数据存储在本地，无需担心数据泄露
   - 建议定期备份chroma-data目录

3. **大模型配置**
   - 确保LM Studio已正确配置并运行
   - 选择适合您硬件的模型大小

4. **依赖管理**
   - 定期更新依赖包以获取最新功能和安全修复

## 未来规划

1. 支持更多文档格式
2. 优化文档分块算法
3. 增加文档摘要功能
4. 支持多语言模型
5. 提供更多个性化配置选项

## 贡献

欢迎提交Issue和Pull Request，共同改进这个项目！
