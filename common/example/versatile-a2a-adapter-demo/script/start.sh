#!/usr/bin/env bash
#
# start.sh — 启动 versatile-a2a-adapter-demo 的 jar，拉起一个只挂载 VersatileAdapter 的 A2A runtime。
#
# 用法:
#   ./start.sh                 # 默认端口 18080，默认远端 Versatile 地址
#   ./start.sh --stop          # 停止之前启动的进程（记录 PID 在 .demo.pid）
#   SERVER_PORT=9090 ./start.sh
#   VERSATILE_URL="http://host:port/v1/.../{conversation_id}" ./start.sh
#
# 关键环境变量（可覆盖）:
#   SERVER_PORT    本地 A2A runtime 监听端口，默认 18080
#   VERSATILE_URL  远端 Versatile HTTP 地址模板，默认
#                  http://127.0.0.1:31113/v1/0/agents/{agent_id}/conversations/{conversation_id}
#                  {conversation_id} 会被 adapter 替换为请求里的 contextId；
#                  {agent_id} 会被替换为 params.metadata.agent_id（缺失则替换为空串）
#   JAR            显式指定 jar 路径；不指定时自动探测 target/versatile-a2a-adapter-demo-*.jar
#
# 启动后 A2A 入口为: http://127.0.0.1:${SERVER_PORT}/a2a/
# 之后用 ./send-requests.sh 发送 curl 请求。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEMO_DIR="$(dirname "$SCRIPT_DIR")"
PID_FILE="$SCRIPT_DIR/.demo.pid"

# ---------- 处理 --stop ----------
if [[ "${1:-}" == "--stop" ]]; then
  if [[ -f "$PID_FILE" ]]; then
    PID="$(cat "$PID_FILE")"
    if kill -0 "$PID" 2>/dev/null; then
      echo "Stopping demo (pid=$PID) ..."
      kill "$PID"
      rm -f "$PID_FILE"
    else
      echo "PID $PID not running, removing stale pid file."
      rm -f "$PID_FILE"
    fi
  else
    echo "No pid file found (${PID_FILE}). Nothing to stop."
  fi
  exit 0
fi

# ---------- 解析 jar ----------
# 兼容三种布局:
#   仓库/容器: target/versatile-a2a-adapter-demo-*.jar
#   生产包:    解包根目录下的 versatile-a2a-adapter-demo-*.jar
JAR="${JAR:-}"
if [[ -z "$JAR" ]]; then
  JAR="$(ls "$DEMO_DIR"/target/versatile-a2a-adapter-demo-*.jar 2>/dev/null | head -1 || true)"
fi
if [[ -z "$JAR" ]]; then
  JAR="$(ls "$DEMO_DIR"/versatile-a2a-adapter-demo-*.jar 2>/dev/null | head -1 || true)"
fi
if [[ -z "$JAR" || ! -f "$JAR" ]]; then
  echo "ERROR: jar not found under $DEMO_DIR/target/ or $DEMO_DIR/ (versatile-a2a-adapter-demo-*.jar)" >&2
  echo "Build it first (from repo root):" >&2
  echo "  mvn -f common/example/versatile-a2a-adapter-demo/pom.xml clean package -DskipTests" >&2
  exit 1
fi

# ---------- 环境变量 ----------
export SERVER_PORT="${SERVER_PORT:-18080}"
# 注意: VERSATILE_URL 默认值含 {conversation_id}，不能写成
#   export VERSATILE_URL="${VERSATILE_URL:-http://.../conversations/{conversation_id}}"
# bash 会把 {conversation_id} 的 } 误认为参数展开的结束符，导致展开后残留一个 '}'，
# 使 VERSATILE_URL 变成 .../{conversation_id}}（替换后 URL 出现 .../xxxx}）。
# 因此用 if + 直接赋值，避开 ${var:-default} 展开。
if [[ -z "${VERSATILE_URL:-}" ]]; then
  export VERSATILE_URL="http://127.0.0.1:31113/v1/0/agents/{agent_id}/conversations/{conversation_id}"
fi

# ---------- 检查端口是否被占用 ----------
if curl -s -o /dev/null --max-time 1 "http://127.0.0.1:${SERVER_PORT}/a2a/" 2>/dev/null; then
  echo "WARN: http://127.0.0.1:${SERVER_PORT}/a2a/ already responds — maybe already running?" >&2
fi

# ---------- 日志目录（生产包无 target/ 时落到 logs/） ----------
LOG_DIR="$DEMO_DIR/target"
if [[ ! -d "$LOG_DIR" ]]; then
  LOG_DIR="$DEMO_DIR/logs"
  mkdir -p "$LOG_DIR"
fi
LOG_FILE="$LOG_DIR/demo.log"

# ---------- 启动 ----------
echo "Starting demo jar: $JAR"
echo "  A2A entry       = http://127.0.0.1:${SERVER_PORT}/a2a/"
echo "  VERSATILE_URL   = ${VERSATILE_URL}"
echo "  log             = $LOG_FILE (tail -f 查看 Versatile remote request / outbound request)"
nohup java -jar "$JAR" > "$LOG_FILE" 2>&1 &
echo $! > "$PID_FILE"
echo "Started pid=$(cat "$PID_FILE")"

# ---------- 等待就绪 ----------
for i in $(seq 1 30); do
  if curl -s -o /dev/null --max-time 1 "http://127.0.0.1:${SERVER_PORT}/a2a/" 2>/dev/null; then
    echo "Ready after ${i}s: http://127.0.0.1:${SERVER_PORT}/a2a/"
    echo "Next: ./script/send-requests.sh"
    exit 0
  fi
  sleep 1
done
echo "WARN: service did not respond within 30s. Check $LOG_FILE" >&2
exit 1