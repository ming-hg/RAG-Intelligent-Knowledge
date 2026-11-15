#!/usr/bin/env bash
# 端到端冒烟的入口。它只负责三件事：挑一个够新的 node、确认后端在、把参数原样交给 smoke.mjs。
#
#   deploy/smoke.sh                 # 非破坏性全流程
#   deploy/smoke.sh --archive       # 加跑归档导出/删除/导入（会删该库全部文档再导回来）
#   deploy/smoke.sh --help          # 全部参数
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(dirname "$HERE")"
cd "$ROOT"

# .env 里可以写 RAG_API（整段地址）或 RAG_API_PORT（只写端口）；都没写就用本机默认端口
if [ -f .env ]; then
  # 只取需要的两行，避免把整份 .env 灌进当前 shell（里面还有数据库口令）
  for key in RAG_API RAG_API_PORT; do
    line="$(grep -E "^[[:space:]]*(export[[:space:]]+)?${key}=" .env | tail -1 || true)"
    # 用记事本改过 .env 就会带 CR，留着它 curl 会去连 "8080\r"
    [ -n "$line" ] && export "${key}=$(printf %s "${line#*=}" | tr -d '\r')"
  done
fi
BASE="${RAG_API:-http://localhost:${RAG_API_PORT:-8080}}"
for arg in "$@"; do
  case "$arg" in --base=*) BASE="${arg#--base=}" ;; esac
done

NODE="${NODE_BIN:-$(command -v node || true)}"
if [ -z "$NODE" ]; then
  echo "找不到 node。smoke.mjs 需要 Node 20.19+ / 22.12+（前端同款要求）。" >&2
  exit 2
fi
if ! "$NODE" -e 'const [a,b]=process.versions.node.split(".").map(Number);process.exit(a>20||(a===20&&b>=19)?0:1)'; then
  echo "node 版本太低：$("$NODE" -v)，smoke.mjs 需要 20.19+ 或 22.12+（FormData/Blob 在那之前是实验特性）。" >&2
  echo "换一个大版本再跑，例如 nvm use 22，或者 NODE_BIN=/path/to/node deploy/smoke.sh" >&2
  exit 2
fi

if ! curl -fsS -m 5 -o /dev/null "${BASE}/api/health" 2>/dev/null; then
  echo "后端没在 ${BASE} 上应答。先 deploy/start.sh，或者 --base=http://主机:8080 指到别处。" >&2
  exit 2
fi

echo "冒烟目标：${BASE}（node $("$NODE" -v)）"
exec "$NODE" "$HERE/smoke.mjs" --base="$BASE" "$@"
