#!/bin/bash
# ============================================================
# stop.sh — 停止后台运行的服务
#
# 用法：./stop.sh
# ============================================================

WORKSHOP_DIR="$(cd "$(dirname "$0")" && pwd)"
PID_DIR="$WORKSHOP_DIR/.pids"

echo "=== 停止服务 ==="

for name in db-connector-mcp; do
    PID_FILE="$PID_DIR/$name.pid"
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if kill -0 "$PID" 2>/dev/null; then
            kill "$PID"
            echo "  已停止 $name (PID: $PID)"
        else
            echo "  $name (PID: $PID) 已不在运行"
        fi
        rm -f "$PID_FILE"
    else
        echo "  $name 未启动（无 PID 文件）"
    fi
done

echo ""
echo "=== 完成 ==="
