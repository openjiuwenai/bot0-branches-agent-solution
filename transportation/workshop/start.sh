#!/bin/bash
# ============================================================
# start.sh — 启动 db-connector MCP 服务
#
# 用法：
#   ./start.sh                      # 使用默认配置启动（stdio 传输）
#   DB_CONNECTOR_CONFIG=/path/config.yaml MCP_TRANSPORT=sse MCP_PORT=8080 ./start.sh
#
# 后台运行，日志输出到 .logs/ 目录，PID 记录到 .pids/ 目录。
# 停止服务请运行：./stop.sh
# ============================================================

set -e

WORKSHOP_DIR="$(cd "$(dirname "$0")" && pwd)"
TOOLS_DIR="$WORKSHOP_DIR/tools/db-connector"
MCP_DIR="$WORKSHOP_DIR/mcp/db-connector-server"
PID_DIR="$WORKSHOP_DIR/.pids"
LOG_DIR="$WORKSHOP_DIR/.logs"

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
MCP_PORT="${MCP_PORT:-8080}"

echo ""
echo "=== 启动 db-connector MCP 服务 ==="
echo "  配置: $DB_CONFIG"
echo "  传输: $MCP_TRANSPORT"

nohup python "$MCP_DIR/server.py" "$DB_CONFIG" \
    --transport "$MCP_TRANSPORT" \
    --port "$MCP_PORT" \
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
    echo "    地址: http://localhost:$MCP_PORT"
fi
echo "    日志: $LOG_DIR/db-connector-mcp.log"
echo ""
echo "  停止服务: ./stop.sh"
echo "  查看日志: tail -f $LOG_DIR/db-connector-mcp.log"
echo ""
