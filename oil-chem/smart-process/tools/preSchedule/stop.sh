#!/usr/bin/env bash
# 一键停止前后端（Ubuntu 环境）
#
# 优先用 logs/*.pid 精确停止；若无 pid 文件（如手动启动），按端口匹配兜底。
# 配套 start.sh，端口：后端 8100 / 前端 3100。
#
# 用法：
#   ./stop.sh          停止前后端
#   ./stop.sh backend  仅停后端
#   ./stop.sh frontend 仅停前端

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$ROOT/logs"
BACKEND_PORT=8100
FRONTEND_PORT=3100

TARGET="${1:-all}"   # all | backend | frontend

# kill_by_pidfile <name> <pidfile>
# 用 pid 文件精确停进程；顺带清理 pid 文件。
kill_by_pidfile() {
  local name="$1" pidfile="$2"
  if [[ -f "$pidfile" ]]; then
    local pid
    pid="$(cat "$pidfile" 2>/dev/null || true)"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
      # 先 TERM 优雅停，1s 后仍存活则 KILL
      kill "$pid" 2>/dev/null || true
      sleep 1
      kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
      echo "✅ 已停止 $name（PID=$pid，按 pid 文件）"
    else
      echo "ℹ $name pid 文件存在但进程已不在（PID=$pid）"
    fi
    rm -f "$pidfile"
    return 0
  fi
  return 1   # 无 pid 文件
}

# kill_by_pattern <name> <pgrep 模式>
# 无 pid 文件时按进程命令行模式兜底匹配。
kill_by_pattern() {
  local name="$1" pattern="$2"
  local pids
  pids="$(pgrep -f "$pattern" 2>/dev/null || true)"
  if [[ -z "$pids" ]]; then
    echo "ℹ $name 未在运行"
    return 0
  fi
  # shellcheck disable=SC2086
  kill $pids 2>/dev/null || true
  sleep 1
  # 仍存活的强杀
  local still
  still="$(pgrep -f "$pattern" 2>/dev/null || true)"
  if [[ -n "$still" ]]; then
    # shellcheck disable=SC2086
    kill -9 $still 2>/dev/null || true
  fi
  echo "✅ 已停止 $name（按模式 '$pattern' 匹配，原 PID: $(echo $pids | tr '\n' ' ')）"
}

stop_backend() {
  kill_by_pidfile "后端" "$LOG_DIR/backend.pid" \
    || kill_by_pattern "后端" "uvicorn api_server:app.*--port $BACKEND_PORT"
}

stop_frontend() {
  kill_by_pidfile "前端" "$LOG_DIR/frontend.pid" \
    || kill_by_pattern "前端" "next dev -p $FRONTEND_PORT"
}

case "$TARGET" in
  all)      stop_backend; stop_frontend ;;
  backend)  stop_backend ;;
  frontend) stop_frontend ;;
  *) echo "用法: $0 [all|backend|frontend]" >&2; exit 1 ;;
esac
