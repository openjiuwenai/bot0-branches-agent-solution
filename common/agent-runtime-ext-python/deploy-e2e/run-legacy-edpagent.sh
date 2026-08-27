#!/usr/bin/env bash
# 部署级 E2E：存量方式起真 EDPAgent——`.env` 原样、代码原位、启动命令只改模块名。
#
# 验的是升级说明的那句承诺本身：部署方把 `applications/a2a_service` 换成本版后，
# 起来的是**他的 EDPAgent**，不是替身。此前 `run-legacy-env-parity.sh` 只验「起得来」
# （`RUNTIME_BACKEND=fixture`），EDPAgent 在本仓的部署级验证里只在存量侧起过。
#
# 两种受测形态都支持，由 E2E_BACKEND 选（与 _backend.sh 同一约定）：
#   local   直接在本机进程里起（venv + PYTHONPATH），与部署方在宿主机上直接跑同构
#   docker  在容器里起（Dockerfile.legacy-edpagent，同时是部署文档「容器化」一节的样本）
#   auto    有容器运行时用 docker，否则 local（默认）
# EDPAgent 未配置沙箱时 `execute_cmd` 直接在进程所在机器上跑 shell（实测一次真模型回合就
# 执行了 `find /`）。local 形态下它跑在验证机上——验证环境信任 Agent 是既定裁定；
# 容器形态是生产部署的形态，也是不信任时的边界。
#
# 前置，缺一即退 3（未判，不是通过）：
#   1. 存量副本已导出（`tools/legacy_oracle.sh fetch`，它会把 EDPAgent 拷到存量落位）
#   2. 模型凭据 LLM_BASE / LLM_API_KEY / LLM_MODEL——EDPAgent 启动期就校验模型配置
#   3. Redis（本机可达的一个，或容器运行时可起一个）
#
# 真实模型的答复不确定，故只断言**形态**：装载与初始化日志、健康端点、同步信封的
# 成功位与非空答复、流式至少产帧并收尾；答复内容不比。
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
source "$HERE/_backend.sh"

ORACLE_BASE="$ROOT/.legacy-oracle"
ORACLE="$ORACLE_BASE/applications/a2a_service"
PORT="${LEGACY_EDPAGENT_PORT:-18102}"
REDIS_PORT="${LEGACY_EDPAGENT_REDIS_PORT:-16380}"
REDIS_NAME="legacy-edpagent-redis"
IMAGE="${LEGACY_EDPAGENT_IMAGE:-agent-runtime-legacy-edpagent:local}"
CONTAINER="legacy-edpagent-sut"
AGENT_ID="edp_agent"
STAGE="$(mktemp -d)"
ENVFILE="$STAGE/.env"
LOGDIR="$HERE/.e2e-logs"
LOG="$LOGDIR/legacy-edpagent.log"
PID=""
REDIS_STARTED=0
MODE=""

log() { echo "[e2e-legacy-edpagent] $*"; }
unjudged() { log "⏭ $1"; log "   未判不等于通过"; exit 3; }
dump_log() { [ "$MODE" = "docker" ] && { docker logs "$CONTAINER" > "$LOG" 2>&1 || true; }; return 0; }
fail() { log "❌ $1"; dump_log; tail -40 "$LOG" 2>/dev/null | sed 's/^/     /'; exit 1; }
kill_by_port() {
    local pid
    pid=$(ss -lptn "sport = :$1" 2>/dev/null | grep -oP 'pid=\K[0-9]+' | head -1)
    [ -n "$pid" ] && kill -9 "$pid" 2>/dev/null
    return 0
}
cleanup() {
    [ -n "$PID" ] && kill "$PID" 2>/dev/null
    kill_by_port "$PORT"
    docker rm -f "$CONTAINER" >/dev/null 2>&1
    [ "$REDIS_STARTED" = "1" ] && docker rm -f "$REDIS_NAME" >/dev/null 2>&1
    rm -rf "$STAGE"
    return 0
}
trap cleanup EXIT INT TERM
mkdir -p "$LOGDIR"

# ── 前置 ────────────────────────────────────────────────────────────
case "${E2E_BACKEND:-auto}" in
    docker|local) MODE="$E2E_BACKEND" ;;
    auto) if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then MODE=docker; else MODE=local; fi ;;
    *) log "❌ E2E_BACKEND 取值非法：$E2E_BACKEND（可选 docker|local|auto）"; exit 2 ;;
esac
[ -f "$ORACLE/.env.example" ] || unjudged "存量副本未导出（缺 $ORACLE/.env.example）：先跑 tools/legacy_oracle.sh fetch"
[ -f "$ORACLE/agents/EDPAgent/__init__.py" ] || unjudged "存量落位里没有 EDPAgent（$ORACLE/agents/EDPAgent）：导出脚本会从 agent-solution 拷入，检查其源是否在场"
[ -n "${LLM_API_KEY:-}" ] && [ -n "${LLM_BASE:-}" ] && [ -n "${LLM_MODEL:-}" ] || unjudged "缺模型凭据（需 LLM_BASE / LLM_API_KEY / LLM_MODEL 三个都给）：EDPAgent 启动期校验模型配置，缺一起不来"
if [ "$MODE" = "docker" ]; then
    command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 || unjudged "E2E_BACKEND=docker 但无容器运行时"
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
fi
kill_by_port "$PORT"
if ! (echo > "/dev/tcp/127.0.0.1/$REDIS_PORT") 2>/dev/null; then
    command -v docker >/dev/null 2>&1 || unjudged "无 Redis（127.0.0.1:$REDIS_PORT）且无容器运行时可起一个"
    docker run -d --rm --name "$REDIS_NAME" -p "$REDIS_PORT:6379" redis:7-alpine >/dev/null 2>&1 \
        || unjudged "Redis 容器起不来"
    REDIS_STARTED=1
    sleep 2
fi
log "受测形态：$MODE"

# ── 1/5 环境：存量 .env.example 全部 + 本机覆盖（与 run-legacy-env-parity 同法）──
log "1/5 以存量 .env.example 为基底构造环境，模型与 Redis 按本机覆盖"
grep -vE '^\s*(#|$)' "$ORACLE/.env.example" > "$ENVFILE"
cat >> "$ENVFILE" <<EOF
FASTAPI_HOST=127.0.0.1
FASTAPI_PORT=$PORT
FASTAPI_WORKERS=1
REDIS_HOST=127.0.0.1
REDIS_PORT=$REDIS_PORT
REDIS_DB=0
REDIS_PASSWORD=
DPA_AGENT_ID=$AGENT_ID
PLANNING_AGENT_MODEL_BASE_URL=$LLM_BASE
PLANNING_AGENT_MODEL_API_KEY=$LLM_API_KEY
PLANNING_AGENT_MODEL_NAME=$LLM_MODEL
BOOTSTRAP_COORDINATION_ENABLED=false
EOF
log "   取到 $(grep -c '=' "$ENVFILE") 项（存量 .env.example 全部 + 本机覆盖）"

# ── 2/5 起受测端：不设 RUNTIME_BACKEND → 按存量导入名装载 agents.EDPAgent ──
if [ "$MODE" = "docker" ]; then
    log "2/5 拼装构建上下文并构建镜像，起容器（host 网络，端口 $PORT）"
    mkdir -p "$STAGE/ctx/legacy"
    cp -r "$ROOT/agent_runtime" "$STAGE/ctx/agent_runtime"
    cp -r "$ROOT/deploy" "$STAGE/ctx/deploy"
    cp -r "$ORACLE/agents" "$STAGE/ctx/legacy/agents"
    cp -r "$ORACLE/common" "$STAGE/ctx/legacy/common"
    find "$STAGE/ctx" -name "__pycache__" -type d -exec rm -rf {} + 2>/dev/null
    docker build -q -f "$HERE/Dockerfile.legacy-edpagent" -t "$IMAGE" "$STAGE/ctx" >/dev/null \
        || { log "❌ 镜像构建失败"; docker build -f "$HERE/Dockerfile.legacy-edpagent" -t "$IMAGE" "$STAGE/ctx" 2>&1 | tail -30 | sed 's/^/     /'; exit 1; }
    sed -i 's/^FASTAPI_HOST=.*/FASTAPI_HOST=0.0.0.0/' "$ENVFILE"
    docker run -d --name "$CONTAINER" --network host --env-file "$ENVFILE" "$IMAGE" >/dev/null 2>&1 \
        || fail "容器启动失败"
    alive() { [ -n "$(docker ps -q -f "name=^${CONTAINER}$")" ]; }
else
    log "2/5 起本机进程（存量落位目录、本仓根、参考宿主依次上路径）"
    ENV_ARGS=()
    while IFS= read -r line; do ENV_ARGS+=("$line"); done < "$ENVFILE"
    env "${ENV_ARGS[@]}" \
        PYTHONPATH="$ORACLE:$ROOT:$ROOT/deploy:$ORACLE_BASE/foundation:$ORACLE_BASE/service${PYTHONPATH:+:$PYTHONPATH}" \
        "$(_e2e_python)" -m agent_runtime.bootstrap.legacy_compat > "$LOG" 2>&1 &
    PID=$!
    alive() { kill -0 "$PID" 2>/dev/null; }
fi
ready=0
for _ in $(seq 1 180); do
    alive || fail "受测端进程已退出"
    if curl -sf -m 3 "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then ready=1; break; fi
    sleep 1
done
[ "$ready" -eq 1 ] || fail "180s 内未就绪"
dump_log
grep -q "按存量导入名装载宿主 Agent：agent_id=$AGENT_ID" "$LOG" \
    || fail "启动日志里没有「按存量导入名装载宿主 Agent」——起来的不是 EDPAgent"
grep -q "宿主 Agent 就绪：agent_id=$AGENT_ID" "$LOG" \
    || fail "宿主 Agent 未就绪（initialize 没有在启动阶段跑完）"
log "   ✅ 起得来、EDPAgent 已按存量导入名装载并完成初始化"

# ── 3/5 同步请求：存量的自定义 REST 路径与请求体 ──
log "3/5 同步请求（真模型，只断言形态；最长 300s）"
BASE="http://127.0.0.1:$PORT"
CONV="legacy-edp-$$"
RESP=$(curl -s -m 300 -H 'Content-Type: application/json' \
    "$BASE/v1/proj/agents/$AGENT_ID/conversations/$CONV" \
    -d '{"input":{"query":"你好"},"stream":false}')
[ -n "$RESP" ] || fail "同步请求 300s 内无响应体"
python3 - "$RESP" <<'PY' || fail "同步信封不合形态：$RESP"
import json, sys
body = json.loads(sys.argv[1])
assert body.get("success") is True, body
assert isinstance(body.get("answer"), str) and body["answer"].strip(), body
PY
log "   ✅ success=true 且 answer 非空"

# ── 4/5 流式请求：至少产帧且收尾 ──
log "4/5 流式请求（最长 300s）"
STREAM=$(curl -s -N -m 300 -H 'Content-Type: application/json' \
    "$BASE/v1/proj/agents/$AGENT_ID/conversations/$CONV-s" \
    -d '{"input":{"query":"你好"},"stream":true}')
FRAMES=$(printf '%s\n' "$STREAM" | grep -c '^data:')
[ "$FRAMES" -ge 1 ] || fail "流式未产任何 data: 帧"
log "   ✅ 收到 $FRAMES 帧并正常收尾"

# ── 5/5 收尾 ──
dump_log
log "5/5 ✅ 存量方式起真 EDPAgent（$MODE）：通过；受测端日志 $LOG"
exit 0
