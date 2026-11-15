# RAG-Intelligent-Knowledge
本项目是一套前后端分离、全本地私有化部署的RAG（检索增强生成）智能知识库问答系统。 系统对接LM Studio本地部署的开源大模型，支持用户上传私有文档（PDF/DOCX/Markdown/TXT等），自动完成文档解析、语义分块、向量化存储与检索，实现基于私有文档的精准问答，全程数据不离开本地设备，彻底解决大模型知识滞后、幻觉严重、敏感数据无法上云的核心痛点。

## 重构基线

需求、架构、实现已全部重构：前端 Vue 3 + TypeScript，后端 Spring Boot 4 + LangChain4j + PostgreSQL 17/pgvector。
执行基线与决策记录见 [docs/重构方案.md](docs/重构方案.md)，远程模型服务接入见 [docs/远程模型服务接入.md](docs/远程模型服务接入.md)。

## 目录

| 目录 | 说明 |
|---|---|
| `rag-api` | 新后端（Java 17 + Boot 4.1.2），M1/M2/M3/M5a/M5c 已落地 |
| `rag-web` | 前端（Vue 3.5 + TS + Vite 8），M4/M5a/M5c 已落地 |
| `rag-server` | 旧 Node 实现，M6 验收通过后删除 |
| `deploy` | `docker-compose.yml` 与自建的 zhparser PG 镜像 |
| `docs` | 方案、ADR、硬性约束、接入手册 |

## 跑起来

```bash
# 1) 数据库（需要 Docker 引擎可用：Windows 上先确认 BIOS/WSL2 虚拟化已开）
docker compose -f deploy/docker-compose.yml up -d

# 2) 后端：默认值已按本机实测环境写好，不用改配置
cd rag-api && mvn -o spring-boot:run

# 3) 前端：Vite 8 需要 Node 20.19+ / 22.12+，/api 已配好代理，不用写后端地址
cd rag-web && npm ci && npm run dev    # http://localhost:5173

# 4) 自检：分层报告 PG / chat / embedding，models=true 时校验模型名回显
curl "http://localhost:8080/api/health?models=true"

# 5) 上传一份文档，然后轮询进度直到 status=READY
curl -F "file=@../docs/重构方案.md" http://localhost:8080/api/documents
curl http://localhost:8080/api/documents

# 6) 这篇文档想换分块尺寸：五个键全给（null = 跟随全局默认），覆盖只在下次「重算」时生效
curl http://localhost:8080/api/documents/1/chunk-config
curl -X PUT -H "Content-Type: application/json" \
  -d '{"childChars":260,"childMinChars":null,"overlapChars":null,"parentMaxChars":null,"parentChild":null}' \
  http://localhost:8080/api/documents/1/chunk-config
curl -X POST "http://localhost:8080/api/documents/1/reindex?reparse=false"
```

前端只认相对路径 `/api`，代理目标由 `rag-web/.env` 的 `VITE_API_TARGET` 决定，默认 `http://localhost:8080`；
类型检查与构建：`npm run typecheck`、`npm run build`（先 `vue-tsc --noEmit` 再打包）。

不连数据库也能验证的代码层测试（当前 154 例）：

```bash
cd rag-api && mvn -o test
```

检索与问答（文档 `status=READY` 之后；`kbId` 由 `/api/knowledge-bases` 给出，必须显式指定）：

```bash
# 只看召回与引用来源，不生成
curl "http://localhost:8080/api/search?q=怎么分块&kbId=1&topK=5"

# 流式问答：事件序列 meta -> delta* -> done
curl -N -H "Content-Type: application/json" -d '{"question":"这份文档怎么分块的？","kbIds":[1]}' \
  http://localhost:8080/api/chat/ask

# 反馈闭环：只点 👍 不会抹掉已写的反馈文字（缺席=不改，空串=清空）；
# 订正过的答案会顶替模型原文进入下一轮问答的历史
curl -X PATCH -H "Content-Type: application/json" -d '{"rating":1}' \
  http://localhost:8080/api/chat/messages/{answerId}
curl -X PATCH -H "Content-Type: application/json" -d '{"annotatedAnswer":"正确的说法是……"}' \
  http://localhost:8080/api/chat/messages/{answerId}
```

真机模型链路校验（默认跳过，会把地址、模型名、维度、批量、流式一次验完）：

```bash
LM_STUDIO_IT=true mvn -o verify -Dfailsafe.forkedProcessTimeoutInSeconds=240
```

