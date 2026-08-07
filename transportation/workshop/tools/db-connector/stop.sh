#!/bin/bash
# ============================================================
# stop.sh — 停止 db-connector HTTP 服务
#
# 用法：./stop.sh
# ============================================================

TOOL_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_DIR="$TOOL_DIR/.pids"

echo "=== 停止 db-connector HTTP 服务 ==="

PID_FILE="$PID_DIR/db-connector.pid"
if [ -f "$PID_FILE" ]; then
    PID=$(cat "$PID_FILE")
    if kill -0 "$PID" 2>/dev/null; then
        kill "$PID"
        echo "  已停止 db-connector (PID: $PID)"
    else
        echo "  db-connector (PID: $PID) 已不在运行"
    fi
    rm -f "$PID_FILE"
else
    echo "  db-connector 未启动（无 PID 文件）"
fi

echo ""
echo "=== 完成 ==="
