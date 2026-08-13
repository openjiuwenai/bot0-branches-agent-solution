#!/usr/bin/env bash
# 一键启动前后端（Ubuntu 环境）
#
# 端口（与代码实际配置一致，非 wsl环境配置.md 的旧值）：
#   后端 uvicorn → 8100  （前端 next.config.js rewrites 代理到此端口）
#   前端 next dev → 3100 （web/package.json: "dev": "next dev -p 3100"）
#
# 用法：
#   ./start.sh            前台启动（日志直接输出，Ctrl+C 停止全部）
#   ./start.sh -d         后台启动（日志写入 logs/，返回后可用 stop.sh 停止）
#
# 前置：已创建 venv 并 pip 安装依赖、web/ 下已 npm install（见下方校验提示）。

set -euo pipefail

# ── 路径与端口 ──
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"
WEB_DIR="$ROOT/web"
VENV="$ROOT/.venv"
BACKEND_PORT=8100
FRONTEND_PORT=3100
LOG_DIR="$ROOT/logs"
mkdir -p "$LOG_DIR"

DAEMON=false
[[ "${1:-}" == "-d" ]] && DAEMON=true

# ── 校验前置依赖 ──
if [[ ! -d "$VENV" ]]; then
  echo "❌ 未找到虚拟环境 $VENV，请先创建：python3 -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt" >&2
  exit 1
fi
if [[ ! -d "$WEB_DIR/node_modules" ]]; then
  echo "❌ 未找到 $WEB_DIR/node_modules，请先在 web/ 下执行 npm install。" >&2
  exit 1
fi

# ── 重复启动检测 ──
if pgrep -f "uvicorn api_server:app.*--port $BACKEND_PORT" >/dev/null 2>&1; then
  echo "⚠ 后端已在运行（端口 $BACKEND_PORT），跳过启动。"
  BACKEND_RUNNING=true
else
  BACKEND_RUNNING=false
fi
if pgrep -f "next dev -p $FRONTEND_PORT" >/dev/null 2>&1; then
  echo "⚠ 前端已在运行（端口 $FRONTEND_PORT），跳过启动。"
  FRONTEND_RUNNING=true
else
  FRONTEND_RUNNING=false
fi

start_backend() {
  echo "▶ 启动后端 uvicorn → 127.0.0.1:$BACKEND_PORT"
  # shellcheck disable=SC1091
  source "$VENV/bin/activate"
  if $DAEMON; then
    nohup uvicorn api_server:app --host 127.0.0.1 --port "$BACKEND_PORT" \
      > "$LOG_DIR/backend.log" 2>&1 &
    echo $! > "$LOG_DIR/backend.pid"
    echo "  后台 PID=$(cat "$LOG_DIR/backend.pid")，日志：$LOG_DIR/backend.log"
  else
    exec uvicorn api_server:app --host 127.0.0.1 --port "$BACKEND_PORT"
  fi
}

start_frontend() {
  echo "▶ 启动前端 next dev → 0.0.0.0:$FRONTEND_PORT（可远程访问）"
  cd "$WEB_DIR"
  if $DAEMON; then
    nohup npm run dev -- -H 0.0.0.0 > "$LOG_DIR/frontend.log" 2>&1 &
    echo $! > "$LOG_DIR/frontend.pid"
    echo "  后台 PID=$(cat "$LOG_DIR/frontend.pid")，日志：$LOG_DIR/frontend.log"
  else
    npm run dev -- -H 0.0.0.0
  fi
}

# ── 启动 ──
if $DAEMON; then
  # 后台模式：两个都起，各自写 pid/日志
  $BACKEND_RUNNING || start_backend
  sleep 2   # 等后端先就绪，前端 rewrites 代理才不会首屏报错
  $FRONTEND_RUNNING || start_frontend
  sleep 1
  echo ""
  echo "✅ 已启动（后台模式）："
  echo "   前端： http://$(hostname -I | awk '{print $1}'):$FRONTEND_PORT/    （远程访问用本机 IP）"
  echo "   健康检查(经前端代理)： http://$(hostname -I | awk '{print $1}'):$FRONTEND_PORT/api/health"
  echo "   停止： ./stop.sh"
  echo "   日志： tail -f $LOG_DIR/backend.log $LOG_DIR/frontend.log"
else
  # 前台模式：后端前台 exec（占据本终端），前端需另开终端或改 -d
  if ! $BACKEND_RUNNING; then
    echo "ℹ 前台模式仅启动后端到当前终端；如需同时启动前端，请用 './start.sh -d'。"
    start_backend   # exec，下方代码不会执行
  else
    echo "ℹ 后端已在运行。如需前端，请另开终端执行：cd web && npm run dev"
  fi
fi
