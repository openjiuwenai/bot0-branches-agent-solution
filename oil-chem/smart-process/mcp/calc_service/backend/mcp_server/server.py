# -*- coding: utf-8 -*-
"""计算层 MCP Server — 独立进程入口。

通过 MCP 协议暴露 14 个计算工具：
  - 编排入口层 (2): solve_refinery_plan / optimize_valve_switches
  - 独立计算层 (5): calculate_batch_physical / calculate_batch_full /
                    evaluate_valve_combination / optimize_combinations /
                    init_hangmei_context
  - 分析渲染层 (4): aggregate_batch_economics / render_economic_summary /
                    build_economic_breakdown / analyze_jian1_switch
  - 可视化+数据层 (3): build_flow_diagram / build_device_input_sources /
                    preload_reference_data

支持三种 transport 模式：
  - stdio（默认）：本地 IDE 集成（Trae / Claude Desktop）
    python -m calc_service.backend.mcp_server.server

  - streamable-http：远程 Agent 平台集成（推荐）
    python -m calc_service.backend.mcp_server.server -t streamable-http --host 0.0.0.0 --port 8766
    端点地址：http://<host>:<port>/mcp

  - sse：Server-Sent Events 模式（流式推送，适合长任务监控）
    python -m calc_service.backend.mcp_server.server -t sse --host 0.0.0.0 --port 8766
    端点地址：http://<host>:<port>/sse

  - streamable-http + stateless + json-response：简化版远程平台集成
    python -m calc_service.backend.mcp_server.server -t streamable-http --stateless --json-response
    无需 session 管理，纯 JSON 请求/响应，适配低配 Agent 平台

环境变量：
  MCP_TRANSPORT - 传输协议 stdio|streamable-http|sse（默认 stdio）
  MCP_HOST      - HTTP/SSE 模式监听地址（默认 0.0.0.0）
  MCP_PORT      - HTTP/SSE 模式监听端口（默认 8766）
  DATABASE_URL  - PostgreSQL 连接串（ScenarioAdapter 加载场景用）

端口规划：
  8000  - 慧炼主后端 (FastAPI)
  5081  - 效益预测服务 Flask API（前端交互用）
  8765  - data_service MCP（数据 CRUD）
  8766  - calc_service MCP（本服务，业务计算）
"""
import argparse
import os
import sys

from mcp.server.fastmcp import FastMCP

from .tool_registry import register_calc_tools


def _create_mcp(
    host: str = "0.0.0.0",
    port: int = 8766,
    stateless: bool = False,
    json_response: bool = False,
) -> FastMCP:
    """创建 FastMCP 实例并注册全部计算工具。

    host/port/stateless/json_response 直接传给 FastMCP 构造函数，
    内部自动创建 Settings 对象。
    """
    mcp = FastMCP(
        "calc_service",
        host=host,
        port=port,
        log_level="INFO",
        stateless_http=stateless,
        json_response=json_response,
    )
    register_calc_tools(mcp)
    return mcp


# 默认实例（供 import 使用，如 .mcp.json stdio 模式）
mcp = _create_mcp()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="calc_service MCP Server — 计算层 MCP 服务")
    parser.add_argument(
        "--transport", "-t",
        choices=["stdio", "streamable-http", "sse"],
        default=os.getenv("MCP_TRANSPORT", "stdio"),
        help="传输协议（默认 stdio，环境变量 MCP_TRANSPORT）",
    )
    parser.add_argument(
        "--host",
        default=os.getenv("MCP_HOST", "0.0.0.0"),
        help="HTTP/SSE 模式监听地址（默认 0.0.0.0）",
    )
    parser.add_argument(
        "--port", type=int,
        default=int(os.getenv("MCP_PORT", "8766")),
        help="HTTP/SSE 模式监听端口（默认 8766）",
    )
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

    # HTTP/SSE 模式按参数重建实例（覆盖 host/port/stateless/json_response）
    if args.transport != "stdio":
        mcp = _create_mcp(
            host=args.host,
            port=args.port,
            stateless=args.stateless,
            json_response=args.json_response,
        )

    print(f"[calc_service MCP] transport={args.transport}", file=sys.stderr)
    if args.transport != "stdio":
        mode_parts = []
        if args.transport == "sse":
            mode_parts.append("sse")
            endpoint = f"http://{args.host}:{args.port}/sse"
        else:
            if args.stateless:
                mode_parts.append("stateless")
            if args.json_response:
                mode_parts.append("json-response")
            endpoint = f"http://{args.host}:{args.port}/mcp"
        mode = "+".join(mode_parts) if mode_parts else "stateful"
        print(f"[calc_service MCP] 端点: {endpoint} ({mode})", file=sys.stderr)

    mcp.run(transport=args.transport)
