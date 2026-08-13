# -*- coding: utf-8 -*-
"""data_service MCP Server —— 独立进程，stdio transport。

将 data_service 的 28 个原子工具（15 读 + 13 写）通过 MCP 协议暴露给 AI Agent。

运行：
    python -m backend.data_service.mcp.server
或：
    python backend/data_service/mcp/server.py
"""
