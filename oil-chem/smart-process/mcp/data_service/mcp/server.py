# -*- coding: utf-8 -*-
"""data_service MCP Server —— 独立进程入口。

通过 MCP 协议暴露 28 个原子工具：
  - 读工具 15 个：list_units, list_tanks, list_side_lines, get_yields, ...
  - 写工具 13 个：upsert_unit, upsert_tank, upsert_side_line, ...

支持三种 transport 模式：
  - stdio（默认）：本地 IDE 集成（Trae / Claude Desktop）
    python -m backend.data_service.mcp.server

  - streamable-http：远程 Agent 平台集成（推荐）
    python -m backend.data_service.mcp.server -t streamable-http --host 0.0.0.0 --port 8765
    端点地址：http://<host>:<port>/mcp

  - streamable-http + stateless + json-response：简化版远程平台集成
    python -m backend.data_service.mcp.server -t streamable-http --stateless --json-response
    无需 session 管理，纯 JSON 请求/响应，适配低配 Agent 平台

环境变量：
  MCP_TRANSPORT - 传输协议 stdio|streamable-http|sse（默认 stdio）
  MCP_HOST      - HTTP 模式监听地址（默认 0.0.0.0）
  MCP_PORT      - HTTP 模式监听端口（默认 8765）
  DATABASE_URL  - PostgreSQL 连接串
"""
import argparse
import os
import sys

# 确保独立运行时 mcp 目录在 sys.path(data_service 包在 mcp/data_service/)
# server.py 在 mcp/data_service/mcp/server.py,往上 3 级到 mcp/
_mcp_root = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..', '..'))
if _mcp_root not in sys.path:
    sys.path.insert(0, _mcp_root)

from mcp.server.fastmcp import FastMCP

from data_service.mcp.read_tools import register_read_tools
from data_service.mcp.write_tools import register_write_tools


def _create_mcp(
    host: str = "0.0.0.0",
    port: int = 8765,
    stateless: bool = False,
    json_response: bool = False,
) -> FastMCP:
    """创建 FastMCP 实例并注册全部工具。

    host/port/log_level/stateless/json_response 直接传给 FastMCP 构造函数，
    内部自动创建 Settings 对象（无需显式导入 Settings）。
    """
    mcp = FastMCP(
        "data_service",
        host=host,
        port=port,
        log_level="INFO",
        stateless_http=stateless,
        json_response=json_response,
    )
    register_read_tools(mcp)
    register_write_tools(mcp)
    return mcp


# 默认实例（供 import 使用，如 .mcp.json stdio 模式）
mcp = _create_mcp()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="data_service MCP Server")
    parser.add_argument(
        "--transport", "-t",
        choices=["stdio", "streamable-http", "sse"],
        default=os.getenv("MCP_TRANSPORT", "stdio"),
        help="传输协议（默认 stdio，环境变量 MCP_TRANSPORT）",
    )
    parser.add_argument("--host", default=os.getenv("MCP_HOST", "0.0.0.0"), help="HTTP 模式监听地址")
    parser.add_argument("--port", type=int, default=int(os.getenv("MCP_PORT", "8765")), help="HTTP 模式监听端口")
    parser.add_argument(
        "--stateless",
        action="store_true",
        default=os.getenv("MCP_STATELESS", "").lower() in ("1", "true", "yes"),
        help="无状态模式：每个请求独立，无需 session ID（适合简单 Agent 平台）",
    )
    parser.add_argument(
        "--json-response",
        action="store_true",
        default=os.getenv("MCP_JSON_RESPONSE", "").lower() in ("1", "true", "yes"),
        help="返回纯 JSON 而非 SSE 流（需配合 --stateless 使用）",
    )
    args = parser.parse_args()

    # HTTP 模式按参数重建实例（覆盖 host/port/stateless/json_response）
    if args.transport != "stdio":
        mcp = _create_mcp(
            host=args.host,
            port=args.port,
            stateless=args.stateless,
            json_response=args.json_response,
        )

    print(f"[data_service MCP] transport={args.transport}", file=sys.stderr)
    if args.transport != "stdio":
        mode = "stateless+json" if args.stateless else "stateful"
        print(f"[data_service MCP] 端点: http://{args.host}:{args.port}/mcp ({mode})", file=sys.stderr)

    mcp.run(transport=args.transport)
