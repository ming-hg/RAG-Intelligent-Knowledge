# rag-web

本地知识库问答系统的前端：Vue 3.5 + TypeScript + Vite 8 + Element Plus，全程只走相对路径 `/api`。

```bash
npm ci
npm run dev        # http://localhost:5173 ，Node 需 20.19+ / 22.12+
npm run typecheck  # vue-tsc --noEmit
npm run build      # 先类型检查再打包
```

代理目标默认 `http://localhost:8080`，改后端地址用 `VITE_API_TARGET`（写在 `rag-web/.env.local`，不入库）。

- `src/types/api.ts` 是后端 record 的逐字段镜像，也是两侧唯一的契约文档；改后端字段必须同步这里。
- `src/api/sse.ts` 按 SSE 规范自己分帧（问答是 POST，`EventSource` 用不了），不自动重连。
- 后端与数据库的启动方式见仓库根 README，架构与决策见 `docs/重构方案.md`。
