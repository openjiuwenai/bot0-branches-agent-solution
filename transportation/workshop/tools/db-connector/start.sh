#!/bin/bash
# ============================================================
# start.sh — 启动 db-connector HTTP 服务（后台）
#
# 用法：
#   ./start.sh                      # 默认配置 + 7087 端口
#   DB_CONNECTOR_CONFIG=./config/config.yaml DB_CONNECTOR_PORT=7087 ./start.sh
#   PYTHON_BIN=/usr/bin/python3.10 ./start.sh   # 指定 Python
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
# 0. 选择 Python 解释器（默认优先 python3）
# ------------------------------------------------------------
PYTHON_BIN="${PYTHON_BIN:-}"
if [ -z "$PYTHON_BIN" ]; then
    if command -v python3 >/dev/null 2>&1; then
        PYTHON_BIN="python3"
    elif command -v python >/dev/null 2>&1; then
        PYTHON_BIN="python"
    else
        echo "错误：未找到 python / python3，请先安装 Python 3.9+ 或通过 PYTHON_BIN 指定路径" >&2
        exit 1
    fi
fi

# 校验 Python 版本 >= 3.9
PY_VERSION="$("$PYTHON_BIN" -c 'import sys;print("%d.%d"%sys.version_info[:2])' 2>/dev/null || echo 0.0)"
PY_MAJOR="${PY_VERSION%%.*}"
PY_MINOR="${PY_VERSION##*.}"
if [ "$PY_MAJOR" -lt 3 ] || { [ "$PY_MAJOR" -eq 3 ] && [ "$PY_MINOR" -lt 9 ]; }; then
    echo "错误：需要 Python 3.9+，当前为 $PY_VERSION（$PYTHON_BIN）" >&2
    exit 1
fi

echo "=== Python: $PYTHON_BIN ($PY_VERSION) ==="

# ------------------------------------------------------------
# 1. 安装依赖（容错：单个包安装失败不中断整体启动）
# ------------------------------------------------------------
echo "=== 安装 db-connector 依赖 ==="
"$PYTHON_BIN" -m pip install -r "$TOOL_DIR/requirements.txt" 2>&1 | tail -3 || \
    echo "  [警告] requirements.txt 部分依赖安装失败，继续尝试启动"
"$PYTHON_BIN" -m pip install -e "$TOOL_DIR" 2>&1 | tail -1 || \
    echo "  [警告] 本包安装失败，尝试直接运行模块"

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

nohup "$PYTHON_BIN" -m db_connector.server "$DB_CONFIG" \
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
