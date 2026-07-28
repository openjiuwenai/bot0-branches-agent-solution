#!/usr/bin/env bash
# 慧炼 MCP Server 启动脚本(Linux/Mac)
# 默认 HTTP 模式 :7489,带 Bearer Token 鉴权

set -e
cd "$(dirname "$0")"

# 鉴权 Token:必填,远程调用(Dify 等)必须配置
export MCP_TOKEN="${MCP_TOKEN:-refinery-route-optimizer-token}"

# 数据目录:默认 ./data,可指向自定义路径
# export MCP_DATA_DIR="/path/to/data"

# 端口:默认 7489
PORT="${PORT:-7489}"
HOST="${HOST:-0.0.0.0}"

echo "============================================"
echo " refinery-route-optimizer MCP Server"
echo " 传输: HTTP (streamable-http)"
echo " 地址: http://${HOST}:${PORT}/mcp"
echo " 鉴权: Bearer Token (MCP_TOKEN 已设置)"
echo "============================================"

exec python server.py --transport http --host "$HOST" --port "$PORT"
