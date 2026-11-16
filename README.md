# RAG-Intelligent-Knowledge
本项目是一套前后端分离、全本地私有化部署的RAG（检索增强生成）智能知识库问答系统。 系统对接LM Studio本地部署的开源大模型，支持用户上传私有文档（PDF/DOCX/Markdown/TXT等），自动完成文档解析、语义分块、向量化存储与检索，实现基于私有文档的精准问答，全程数据不离开本地设备，彻底解决大模型知识滞后、幻觉严重、敏感数据无法上云的核心痛点。

## 重构基线

需求、架构、实现已全部重构：前端 Vue 3 + TypeScript，后端 Spring Boot 4 + LangChain4j + PostgreSQL 17/pgvector。
执行基线与决策记录见 [docs/重构方案.md](docs/重构方案.md)，远程模型服务接入见 [docs/远程模型服务接入.md](docs/远程模型服务接入.md)。
Docker 引擎能应答后的连库实测按 [docs/实测指导.md](docs/实测指导.md) 逐步跑，样本文件在 `samples/`（含两份故意失败的乱码/空正文文件）。

## 目录

| 目录 | 说明 |
|---|---|
| `rag-api` | 新后端（Java 17 + Boot 4.1.2），M1/M2/M3/M5a/M5b/M5c/M5d/M5e/M5f/M6 已落地 |
| `rag-web` | 前端（Vue 3.5 + TS + Vite 8），M4/M5a/M5b/M5c/M5d 已落地（M5e 评测只有接口，没做页面） |
| `rag-server` | 旧 Node 实现，M6 验收通过后删除 |
| `deploy` | `start.sh` / `stop.sh` / `smoke.sh` 与 `docker-compose.yml`、自建的 zhparser PG 镜像 |
| `samples` | 连库实测用的样本文档（事实写死，可当场判定对错） |
| `docs` | 方案与 21 条 ADR、硬性约束、接入手册、连库实测指导；另有 `简历项目经历.md` / `面试学习手册.md`（数字全部可回查代码） |

## 跑起来

```bash
cp .env.example .env      # 默认值就是本机实测环境；只改 LM_STUDIO_BASE_URL 与两个模型名
deploy/start.sh           # 起 PG（首次编译 zhparser 镜像 5-15 分钟）→ 打包并起 rag-api → 起 rag-web → 自检门
deploy/smoke.sh           # 端到端回归：health → 上传 samples → 等 READY → 三种模式检索 → 流式问答 → 块级禁用可逆 → 评测跑分 → 诊断对账
deploy/stop.sh            # 停；--keep-db 只停应用，--purge-data 才会删数据卷（要输入 PURGE 确认）
```

脚本每一步失败都会停下并给出「去哪儿改什么」，Docker 引擎没应答（本机实测是 Docker Desktop 进程没在运行，
不是虚拟化没开）时不会干等。
`deploy/smoke.sh` 就是这条约定的产物：[docs/实测指导.md](docs/实测指导.md) §4~§12 那段手点验收，它一条命令跑完
（11 步 / 不带 `--archive` 54 项、带 62 项断言），全绿代表这批里程碑没退化。
默认不破坏数据；加 `--archive` 才会删掉该库全部文档再导回来（归档「免重算」只能这么验）——
导出只带 `READY` 文档，所以那两份故意做坏的样本（`04`/`05`）和重复副本（`06`）跑完就不在列表里了，
重跑一次不带 `--archive` 的上传步骤即可恢复；
加 `--models` 让自检真去模型服务探一次 chat/embedding；
`--shape-only` 只验接口契约，用来在没库没模型的环境（包括那份前端联调用的 mock）自检脚本自身。
退出码：0 全绿 / 1 有失败 / 2 连不上后端或 node 版本不够（需要 20.19+ 或 22.12+）。
临时试一个参数不用改 `.env`：`deploy/start.sh` 让命令行的值赢过 `.env`，并打印出被顶掉的是哪几项
（`RAG_LLM_N_CTX=60000 deploy/start.sh` 撞一次 413，模型就把自己的真实窗口写进错误里——LM Studio 没有查询端点）。
最后一步会拿 `/api/diagnostics` 与问答的 `meta` 帧对账：`vectorDegraded` 不为 0 时，前面那批 rrf 分数是关键词路
给的，整批检索结论都不作数——这一条判的正是「刚才那些步骤可不可信」。

日志看 `deploy/logs/rag-api.log`（logback 滚动，UTF-8）：向量降级、上下文截断、无证据拒答这三类「请求成功但结果可疑」
的事件都以 WARN 单独成行，行首带 traceId；`rag-api.out` 只有启动失败（JDK / jar）那一段。

下面是等价的手工步骤，脚本任一步卡住时可以逐条对照：

```bash
# 0) 分词源码包（scws 1.2.3 + zhparser 2.3）只取一次，之后镜像构建全程离线
sh deploy/pg/fetch-vendor.sh

# 1) 数据库（判据：docker info --format '{{.ServerVersion}}' 能打印版本号；只有 Client 说明 Docker Desktop 没在运行）
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build

# 2) 后端：默认值已按本机实测环境写好，不用改配置
cd rag-api && mvn -o spring-boot:run

# 3) 前端：Vite 8 需要 Node 20.19+ / 22.12+，/api 已配好代理，不用写后端地址
cd rag-web && npm ci && npm run dev    # http://localhost:5173

# 4) 自检：分层报告 PG / chat / embedding，models=true 时校验模型名回显
curl "http://localhost:8080/api/health?models=true"

# 4b) 运行诊断：这一轮有没有偷偷降级/截断/拒答，加上当前真正生效的参数与配置自检
curl "http://localhost:8080/api/diagnostics"

# 5) 上传一份文档，然后轮询进度直到 status=READY
curl -F "file=@../docs/重构方案.md" http://localhost:8080/api/documents
curl http://localhost:8080/api/documents

# 6) 这篇文档想换分块尺寸：五个键全给（null = 跟随全局默认），覆盖只在下次「重算」时生效
curl http://localhost:8080/api/documents/1/chunk-config
curl -X PUT -H "Content-Type: application/json" \
  -d '{"childChars":260,"childMinChars":null,"overlapChars":null,"parentMaxChars":null,"parentChild":null}' \
  http://localhost:8080/api/documents/1/chunk-config
curl -X POST "http://localhost:8080/api/documents/1/reindex?reparse=false"

# 7) 只改其中一块的正文：旧向量当场作废，reembed=false 就只改文字、下次再补算
curl -X PUT -H "Content-Type: application/json" \
  -d '{"content":"把解析弄乱的表格手工订正一遍","reembed":true}' \
  http://localhost:8080/api/documents/1/segments/12/content
curl -X POST http://localhost:8080/api/documents/1/segments/12/reembed

# 8) 整库搬家：先导出 zip（vectors=true 才能免重算，files=true 连原件一起走），再导入另一个库
curl -OJ "http://localhost:8080/api/knowledge-bases/1/export?vectors=true&files=false"
curl -F "file=@kb-1-20261115-213000.zip" "http://localhost:8080/api/knowledge-bases/2/import?rebuild=false"
```

导入返回的 `mode` 只有两种：`CARRIED` = 模型名与维度都对得上，向量直接照搬、一次 embedding 都没花；
`REBUILT` = 空间不兼容（或勾了 `rebuild=true`），按重新入库排队，等文档变 READY 后检索才完整。
同 sha256 的文档跳过不覆盖，逐篇说明在 `notes` 里。

前端只认相对路径 `/api`，代理目标由 `rag-web/.env` 的 `VITE_API_TARGET` 决定，默认 `http://localhost:8080`；
类型检查与构建：`npm run typecheck`、`npm run build`（先 `vue-tsc --noEmit` 再打包）。

不连数据库也能验证的代码层测试（当前 260 例）：

```bash
cd rag-api && mvn -o test
```

检索与问答（文档 `status=READY` 之后；`kbId` 由 `/api/knowledge-bases` 给出，必须显式指定）：

```bash
# 只看召回与引用来源，不生成
curl "http://localhost:8080/api/search?q=怎么分块&kbId=1&topK=5"

# 流式问答：事件序列 meta -> delta* -> done
# kbIds 必填（不带给已有会话也不行）——缺范围在起流之前回 400 JSON，不会先建一条流再报错
# Windows/Git Bash 里 body 带中文改用 --data-binary @file.json（控制台是 GBK，直接 -d 送出的是非 UTF-8 字节）
curl -N -H "Content-Type: application/json" -d '{"question":"这份文档怎么分块的？","kbIds":[1]}' \
  http://localhost:8080/api/chat/ask

# 反馈闭环：只点 👍 不会抹掉已写的反馈文字（缺席=不改，空串=清空）；
# 订正过的答案会顶替模型原文进入下一轮问答的历史
curl -X PATCH -H "Content-Type: application/json" -d '{"rating":1}' \
  http://localhost:8080/api/chat/messages/{answerId}
curl -X PATCH -H "Content-Type: application/json" -d '{"annotatedAnswer":"正确的说法是……"}' \
  http://localhost:8080/api/chat/messages/{answerId}
```

评测跑分（M5e，只走检索、不生成答案，一轮几秒钟；没有前端页面，理由见 docs/重构方案.md §20）：

```bash
curl -X POST http://localhost:8080/api/evaluation/cases -H "Content-Type: application/json" \
  -d '{"kbId":1,"question":"数据库备份保留多少天","expectMarker":"35","expectDoc":"运维手册"}'
curl -X POST "http://localhost:8080/api/evaluation/run?kbId=1&mode=hybrid"   # 也可以带 &topK=50 逼一次预算截断
curl "http://localhost:8080/api/evaluation/runs?limit=5"                     # 每轮带参数快照与相对上一轮的 delta
```

`hit@k / mrr` 量的是检索排第几，`refusalAccuracy` 量「库里真没有」有没有被压住，
`truncatedCaseRate` 量装配吃不吃得下——三者刻意分开：召回 6 块只装下 5 块时 `hit@K` 仍是 1.0，
但这一项会读出「模型没读到那 1 块」，指向 `n_ctx` / `parent-max-chars` 而不是阈值。

真机模型链路校验（默认跳过，会把地址、模型名、维度、批量、流式一次验完）：

```bash
LM_STUDIO_IT=true mvn -o verify -Dfailsafe.forkedProcessTimeoutInSeconds=240
```

