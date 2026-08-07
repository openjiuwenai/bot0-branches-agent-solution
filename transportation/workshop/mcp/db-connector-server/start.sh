#!/bin/bash
# ============================================================
# start.sh — 启动 db-connector MCP 服务
#
# 用法：
#   ./start.sh                      # 使用默认配置启动（stdio 传输）
#   MCP_TRANSPORT=streamable-http MCP_PORT=7087 ./start.sh
#   DB_CONNECTOR_CONFIG=/path/config.yaml MCP_TRANSPORT=sse MCP_PORT=8080 MCP_PATH=/db-connector-server ./start.sh
#
# 后台运行，日志输出到 .logs/ 目录，PID 记录到 .pids/ 目录。
# 停止服务请运行：./stop.sh
# ============================================================

set -e

MCP_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKSHOP_DIR="$(cd "$MCP_DIR/../.." && pwd)"
TOOLS_DIR="$WORKSHOP_DIR/tools/db-connector"
PID_DIR="$MCP_DIR/.pids"
LOG_DIR="$MCP_DIR/.logs"

mkdir -p "$PID_DIR" "$LOG_DIR"

# ------------------------------------------------------------
# 1. 安装依赖
# ------------------------------------------------------------
echo "=== 安装 db-connector 工具依赖 ==="
pip install -r "$TOOLS_DIR/requirements.txt" 2>&1 | tail -1
pip install -e "$TOOLS_DIR" 2>&1 | tail -1

echo "=== 安装 MCP 服务依赖 ==="
pip install -r "$MCP_DIR/requirements.txt" 2>&1 | tail -1

# ------------------------------------------------------------
# 2. 启动 db-connector MCP 服务（后台）
# ------------------------------------------------------------
DB_CONFIG="${DB_CONNECTOR_CONFIG:-$MCP_DIR/config/config.example.yaml}"
MCP_TRANSPORT="${MCP_TRANSPORT:-stdio}"
MCP_HOST="${MCP_HOST:-0.0.0.0}"
MCP_PORT="${MCP_PORT:-8080}"
MCP_PATH="${MCP_PATH:-/db-connector-server}"

echo ""
echo "=== 启动 db-connector MCP 服务 ==="
echo "  配置: $DB_CONFIG"
echo "  传输: $MCP_TRANSPORT"
echo "  监听: $MCP_HOST:$MCP_PORT$MCP_PATH"

nohup python "$MCP_DIR/server.py" "$DB_CONFIG" \
    --transport "$MCP_TRANSPORT" \
    --host "$MCP_HOST" \
    --port "$MCP_PORT" \
    --path "$MCP_PATH" \
    > "$LOG_DIR/db-connector-mcp.log" 2>&1 &
MCP_PID=$!
echo "$MCP_PID" > "$PID_DIR/db-connector-mcp.pid"
echo "  PID: $MCP_PID"
echo "  日志: $LOG_DIR/db-connector-mcp.log"

# ------------------------------------------------------------
# 3. 汇总
# ------------------------------------------------------------
echo ""
echo "========================================"
echo "  服务已后台启动"
echo "========================================"
echo ""
echo "  db-connector MCP"
echo "    PID:  $MCP_PID"
echo "    传输: $MCP_TRANSPORT"
if [ "$MCP_TRANSPORT" != "stdio" ]; then
    echo "    地址: http://<本机IP>:$MCP_PORT$MCP_PATH"
    echo "    示例: http://100.100.135.219:$MCP_PORT$MCP_PATH"
fi
echo "    日志: $LOG_DIR/db-connector-mcp.log"
echo ""
echo "  停止服务: ./stop.sh"
echo "  查看日志: tail -f $LOG_DIR/db-connector-mcp.log"
echo ""
