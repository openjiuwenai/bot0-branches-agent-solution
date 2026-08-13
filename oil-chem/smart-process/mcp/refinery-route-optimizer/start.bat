@echo off
REM 慧炼 MCP Server 启动脚本(Windows)
REM 默认 HTTP 模式 :7489,带 Bearer Token 鉴权

cd /d "%~dp0"

REM 鉴权 Token:必填,远程调用(Dify 等)必须配置
if "%MCP_TOKEN%"=="" set MCP_TOKEN=refinery-route-optimizer-token

REM 端口:默认 7489
if "%PORT%"=="" set PORT=7489
if "%HOST%"=="" set HOST=0.0.0.0

echo ============================================
echo  refinery-route-optimizer MCP Server
echo  传输: HTTP (streamable-http)
echo  地址: http://%HOST%:%PORT%/mcp
echo  鉴权: Bearer Token (MCP_TOKEN 已设置)
echo ============================================

python server.py --transport http --host %HOST% --port %PORT%
