#!/usr/bin/env bash
# 一键起：PostgreSQL 容器 → rag-api → rag-web → 自检门。
# 每步失败都当场停下并说清「去哪儿改什么」——半死不活的栈比明确的报错难查得多。
# 停：deploy/stop.sh。跑之前先看一眼 docs/实测指导.md §0。
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

if [ -f .env ]; then
  # .env 是配置的来源，但调用方显式给的变量要赢：一次性临时覆盖（`RAG_LLM_N_CTX=60000 deploy/start.sh`
  # 撞 413、`RAG_WEB_PORT=5174` 躲占用）如果被 .env 静默盖回去，人会以为改了其实没改。
  # 顶掉的是哪几项当场说出来——优先级反过来一次就够，不该变成新的隐性来源。
  _overridden=() _values=()
  while IFS= read -r _line || [ -n "$_line" ]; do
    _line=${_line#"${_line%%[![:space:]]*}"}   # 去掉行首空白
    case "$_line" in
      export[[:space:]]*) _line=${_line#export}
                          _line=${_line#"${_line%%[![:space:]]*}"} ;;
    esac
    _key=${_line%%=*}
    case "$_key" in [A-Za-z_]*) ;; *) continue ;; esac
    case "$_key" in *[!A-Za-z0-9_]*) continue ;; esac   # 带空格/引号/注释开头的行不是赋值
    if [ -n "${!_key+set}" ]; then
      _overridden+=("$_key")
      _values+=("${!_key}")
    fi
  done <.env
  set -a # shellcheck disable=SC1091
  . ./.env
  set +a
  for _i in "${!_overridden[@]}"; do
    printf -v "${_overridden[_i]}" '%s' "${_values[_i]}"
    export "${_overridden[_i]}"
    echo "   ! ${_overridden[_i]} 用命令行给的值，.env 里这一项本次不生效（想固定就改 .env）"
  done
else
  echo "! 没有 .env：全部用 application.yml 里的默认值。要改模型地址或数据库口令，先 cp .env.example .env"
fi

LOG_DIR="$PWD/deploy/logs"
mkdir -p "$LOG_DIR"

API_PORT="${RAG_API_PORT:-8080}"
WEB_PORT="${RAG_WEB_PORT:-5173}"
PG_USER="${POSTGRES_USER:-rag}"
PG_DB="${POSTGRES_DB:-rag_knowledge}"
API="http://127.0.0.1:$API_PORT"
WEB="http://localhost:$WEB_PORT"

step() { printf '\n== %s\n' "$*"; }
die() {
  printf '\nx %s\n' "$1" >&2
  shift
  for line in "$@"; do printf '    %s\n' "$line" >&2; done
  exit 1
}

step "0/4 环境"
# 便宜的检查先做：zhparser 首次编译要十几分钟，别到第 3 步才发现 node 太老，
# 也别让 docker info 那 15 秒的等待挡在一个几秒钟就能判定的问题前面
command -v java >/dev/null 2>&1 || die "没找到 java 命令" "rag-api 是可执行 jar，需要 JDK/JRE 17"
command -v node >/dev/null 2>&1 || die "没找到 node 命令" "rag-web 需要 Node 20.19+ / 22.12+"
if ! node -e 'const [a,b]=process.versions.node.split(".").map(Number);process.exit(a>20||(a===20&&b>=19)?0:1)'; then
  die "node 版本 $(node -v) 太老，rag-web 起不来" \
    "Vite 8 要求 20.19+ 或 22.12+；本机用 nvm 的话先 nvm use 22 再重跑（后端只认 JDK 17，不受影响）"
fi
echo "   node $(node -v)"

command -v docker >/dev/null 2>&1 || die "没找到 docker 命令" "装好 Docker Desktop 再跑（WSL2 后端）"
# 引擎没起来时 docker info 会一直挂着等，不套 timeout 就变成「脚本卡住不动」这种最难描述的现象
docker_alive() {
  if command -v timeout >/dev/null 2>&1; then
    timeout 15 docker info >/dev/null 2>&1
  else
    docker info >/dev/null 2>&1
  fi
}
if ! docker_alive; then
  die "Docker 引擎没在跑（docker info 15 秒内没返回或失败）" \
    "先分清是哪一种：docker version 只回 Client 且报 npipe ...dockerDesktopLinuxEngine 找不到文件，是 Docker Desktop 进程没启动，直接启动它（约 20 秒应答），跟虚拟化无关" \
    "确认虚拟化：Get-CimInstance Win32_ComputerSystem 的 HypervisorPresent=True 即正常，别去翻 BIOS（Win32_Processor.VirtualizationFirmwareEnabled=False 是假信号）" \
    "验证：docker info --format '{{.ServerVersion}}' 能打印版本号才算通"
fi

step "1/4 PostgreSQL（首次会编译 zhparser 镜像，5-15 分钟属正常）"
# 构建上下文里没有这两个包，BuildKit 报的是「COPY failed」，看不出真正缺的是哪一步
for pkg in scws-1.2.3.tar.gz zhparser-2.3.tar.gz; do
  [ -f "deploy/pg/vendor/$pkg" ] || die "缺少 deploy/pg/vendor/$pkg" \
    "跑一次即可（宿主侧下载 + sha256 校验，之后构建全程离线）：sh deploy/pg/fetch-vendor.sh"
done
docker compose -f deploy/docker-compose.yml up -d --build

pg_status=""
for _ in $(seq 1 90); do
  pg_status=$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' rag-postgres 2>/dev/null || echo missing)
  [ "$pg_status" = healthy ] && break
  sleep 2
done
[ "$pg_status" = healthy ] || die "rag-postgres 180 秒内没到 healthy（当前：${pg_status:-未配置健康检查}）" \
  "看日志：docker compose -f deploy/docker-compose.yml logs --tail 80 postgres"

# 中文分词在不在，直接决定关键词检索走哪一级，值得在启动时就说明白
if [ -n "$(docker exec rag-postgres psql -U "$PG_USER" -d "$PG_DB" -tAc \
  "select 1 from pg_ts_config where cfgname='chinese' limit 1")" ]; then
  echo "   关键词检索：chinese(zhparser) 可用"
else
  echo "   关键词检索：zhparser 不可用，将退化为 pg_trgm 子串匹配（能跑，但召回会差一截）"
fi

step "2/4 rag-api"
command -v mvn >/dev/null 2>&1 || die "没找到 mvn 命令" "需要 Maven 与 JDK 17"
if [ -f "$LOG_DIR/rag-api.pid" ] && kill -0 "$(cat "$LOG_DIR/rag-api.pid")" 2>/dev/null; then
  echo "   已在运行（pid $(cat "$LOG_DIR/rag-api.pid")），跳过启动"
elif curl -sS -m 3 -o /dev/null "$API/api/health" 2>/dev/null; then
  # 端口被人占着又继续往下走，第 4 步的自检就会绿在一个假后端上——这个便宜不能占
  echo "   ! $API 上已经有一个会答 /api/health 的进程，且不是本脚本起的，跳过启动"
  echo "   ! 如果那是 rag-api 的另一个实例（IDE 里跑的、mvn spring-boot:run）没事；"
  echo "   ! 如果是别的东西（比如前端联调用 mock），先停掉它再跑，否则第 4 步的绿是假的"
else
  (cd rag-api && mvn -o -q -DskipTests package) ||
    die "打包失败" "上面是编译错误；离线模式要求本地仓库已有全部依赖，联网时去掉 -o 可补齐"
  jar=$(ls rag-api/target/rag-api-*.jar 2>/dev/null | grep -v '\.original$' | head -1)
  [ -n "$jar" ] || die "没找到可执行 jar" "预期 rag-api/target/rag-api-*.jar，先手工跑一次 mvn -o -DskipTests package"
  # 应用日志由 logback 滚动写到 rag-api.log；这里只收 stdout/stderr，装的是「logback 还没来得及起来」那一段
  nohup java -jar "$jar" >"$LOG_DIR/rag-api.out" 2>&1 &
  echo $! >"$LOG_DIR/rag-api.pid"
  echo "   pid $(cat "$LOG_DIR/rag-api.pid") · 日志 deploy/logs/rag-api.log"
fi

ready=""
for _ in $(seq 1 60); do
  if curl -sS "$API/api/health" 2>/dev/null | grep -Eq '"ready"[[:space:]]*:[[:space:]]*true'; then
    ready=yes
    break
  fi
  sleep 2
done
[ -n "$ready" ] || die "rag-api 120 秒内没就绪" \
  "日志：deploy/logs/rag-api.log（端口冲突、连不上 PG、维度不符都在这里看得见）" \
  "进程根本没起来时看 deploy/logs/rag-api.out（JDK 版本、jar 缺失写在那里）" \
  "自检详情：curl \"$API/api/health?models=true\""

step "3/4 rag-web"
command -v npm >/dev/null 2>&1 || die "没找到 npm 命令" "Vite 8 需要 Node 20.19+ / 22.12+"
[ -d rag-web/node_modules ] || (cd rag-web && npm ci)
if [ -f "$LOG_DIR/rag-web.pid" ] && kill -0 "$(cat "$LOG_DIR/rag-web.pid")" 2>/dev/null; then
  echo "   已在运行（pid $(cat "$LOG_DIR/rag-web.pid")），跳过启动"
else
  nohup npm --prefix rag-web run dev -- --port "$WEB_PORT" >"$LOG_DIR/rag-web.log" 2>&1 &
  echo $! >"$LOG_DIR/rag-web.pid"
fi

web_code=000
for _ in $(seq 1 45); do
  web_code=$(curl -sS -o /dev/null -w '%{http_code}' "$WEB/" 2>/dev/null || echo 000)
  [ "$web_code" = 200 ] && break
  sleep 1
done
[ "$web_code" = 200 ] || die "rag-web 45 秒内没起来（HTTP $web_code）" \
  "日志：deploy/logs/rag-web.log" "端口 $WEB_PORT 被占用时：RAG_WEB_PORT=5174 deploy/start.sh"

step "4/4 自检"
curl -sS "$API/api/health?models=true" | head -c 1200
printf '\n'

cat <<EOF

起来了：
  前端  $WEB/
  后端  $API/api
  日志  deploy/logs/rag-api.log · deploy/logs/rag-web.log
  停止  deploy/stop.sh

下一步（连库实测按 docs/实测指导.md 从 §5 开始）：
  deploy/smoke.sh                 # 一条命令跑完上传→检索→问答的回归，不用手点
  deploy/smoke.sh --archive       # 再加归档导出/删除/导入（会删该库全部文档再导回来）
  curl $API/api/diagnostics       # 刚刚有没有偷偷降级/截断/拒答，以及现在生效的是哪套参数
  手工验证的话：上传 samples/01-运维手册.md，等 status=READY，再问「数据库备份保留多少天」——
  答案里应当出现 35 天，引用指向那篇文档的第 2 节。
EOF
