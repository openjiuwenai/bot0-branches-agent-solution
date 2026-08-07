#!/bin/bash
# ============================================================
# start.sh — 启动 db-connector HTTP 服务（后台）
#
# 用法：
#   ./start.sh                      # 默认配置 + 7087 端口
#   DB_CONNECTOR_CONFIG=./config/config.yaml DB_CONNECTOR_PORT=7087 ./start.sh
#
# 后台运行，日志输出到 .logs/，PID 记录到 .pids/。
# 停止服务请运行：./stop.sh
# ============================================================

set -e

TOOL_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_DIR="$TOOL_DIR/.pids"
LOG_DIR="$TOOL_DIR/.logs"

mkdir -p "$PID_DIR" "$LOG_DIR"

# ------------------------------------------------------------
# 1. 安装依赖
# ------------------------------------------------------------
echo "=== 安装 db-connector 依赖 ==="
pip install -r "$TOOL_DIR/requirements.txt" 2>&1 | tail -1
pip install -e "$TOOL_DIR" 2>&1 | tail -1

# ------------------------------------------------------------
# 2. 启动 HTTP 服务（后台）
# ------------------------------------------------------------
DB_CONFIG="${DB_CONNECTOR_CONFIG:-$TOOL_DIR/config/config.example.yaml}"
DB_HOST="${DB_CONNECTOR_HOST:-0.0.0.0}"
DB_PORT="${DB_CONNECTOR_PORT:-7087}"
DB_PATH="${DB_CONNECTOR_PATH:-/db-connector}"

echo ""
echo "=== 启动 db-connector HTTP 服务 ==="
echo "  配置: $DB_CONFIG"
echo "  监听: $DB_HOST:$DB_PORT$DB_PATH"

nohup python -m openjiuwen.tools.db_connector.server "$DB_CONFIG" \
    --host "$DB_HOST" \
    --port "$DB_PORT" \
    --path "$DB_PATH" \
    > "$LOG_DIR/db-connector.log" 2>&1 &
SERVER_PID=$!
echo "$SERVER_PID" > "$PID_DIR/db-connector.pid"
echo "  PID: $SERVER_PID"
echo "  日志: $LOG_DIR/db-connector.log"

# ------------------------------------------------------------
# 3. 汇总
# ------------------------------------------------------------
echo ""
echo "========================================"
echo "  db-connector HTTP 服务已后台启动"
echo "========================================"
echo ""
echo "  PID:  $SERVER_PID"
echo "  地址: http://<本机IP>:$DB_PORT$DB_PATH"
echo "  示例: http://100.100.135.219:$DB_PORT$DB_PATH"
echo "  日志: $LOG_DIR/db-connector.log"
echo ""
echo "  停止服务: ./stop.sh"
echo "  查看日志: tail -f $LOG_DIR/db-connector.log"
echo ""
