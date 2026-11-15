#!/usr/bin/env bash
# 停：先停自己拉起的 rag-web / rag-api（按 pid 文件），再停数据库容器。
# 默认绝不删数据卷：删库要显式说 --purge-data，且会二次确认。
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
LOG_DIR="$PWD/deploy/logs"

purge_data=no
with_db=yes
for arg in "$@"; do
  case "$arg" in
    --keep-db) with_db=no ;;
    --purge-data) purge_data=yes ;;
    *) printf '未知参数：%s（可用：--keep-db / --purge-data）\n' "$arg" >&2; exit 2 ;;
  esac
done

stop_pid() {
  local name="$1" file="$LOG_DIR/$2"
  [ -f "$file" ] || { echo "   $name：没有 pid 文件，跳过"; return; }
  local pid
  pid=$(cat "$file")
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    for _ in $(seq 1 20); do kill -0 "$pid" 2>/dev/null || break; sleep 0.5; done
    kill -0 "$pid" 2>/dev/null && { kill -9 "$pid" 2>/dev/null || true; echo "   $name：优雅退出超时，已强杀 pid $pid"; } || echo "   $name：已停止 pid $pid"
  else
    echo "   $name：pid $pid 本来就不在"
  fi
  rm -f "$file"
}

echo "== 停止应用进程"
stop_pid rag-web rag-web.pid
stop_pid rag-api rag-api.pid

if [ "$with_db" = yes ] || [ "$purge_data" = yes ]; then
  # 引擎没起来时 docker info 会挂着不返回，这里不套 timeout 就会让人以为停脚本卡死了
  docker_ok=0
  if command -v timeout >/dev/null 2>&1; then
    timeout 15 docker info >/dev/null 2>&1 || docker_ok=$?
  else
    docker info >/dev/null 2>&1 || docker_ok=$?
  fi
  if [ "$docker_ok" != 0 ]; then
    echo "== Docker 引擎不可用，跳过数据库容器操作（引擎起来后再跑一次本脚本即可）"
    exit 0
  fi
fi

if [ "$with_db" = yes ]; then
  echo "== 停止 PostgreSQL 容器（数据卷保留）"
  docker compose -f deploy/docker-compose.yml stop
fi

if [ "$purge_data" = yes ]; then
  printf '\n即将删除数据卷 rag-pg-data：库里的文档、分块与向量会全部消失，且不可恢复。\n'
  read -r -p '确认请输入 PURGE，其他任意输入取消：' answer
  if [ "$answer" != PURGE ]; then
    echo "已取消，数据卷保留。"
  else
    docker compose -f deploy/docker-compose.yml down -v
    echo "数据卷已删除；下次 deploy/start.sh 会重新跑 Flyway 建库。"
  fi
fi

echo
echo "完成。重新启动：deploy/start.sh"
