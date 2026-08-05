"""MCP 服务暴露 — 将 DbConnectorTool 暴露为标准 MCP 服务。

依赖 mcp Python SDK：pip install openjiuwen-db-connector[mcp]
"""

from __future__ import annotations

import sys
from typing import Any

from ..config import load_config
from ..tool import DefaultDbConnectorTool, QueryOptions


def create_server(config_path: str | None = None):
    """创建 MCP Server 并注册工具。

    返回 MCP Server 实例，调用方负责 run()。
    """
    try:
        from mcp.server.fastmcp import FastMCP
    except ImportError as e:
        raise RuntimeError(
            "需安装 mcp SDK：pip install openjiuwen-db-connector[mcp]"
        ) from e

    config = load_config(config_path)
    tool = DefaultDbConnectorTool(config)

    mcp = FastMCP(config.mcp.name)

    @mcp.tool()
    def query(
        sql_template: str,
        params: list[Any] | None = None,
        max_rows: int | None = None,
    ) -> dict:
        """执行参数化查询，返回结构化结果集。"""
        opts = QueryOptions(max_rows=max_rows) if max_rows else None
        return tool.query(sql_template, params or [], opts).to_dict()

    @mcp.tool()
    def insert(sql_template: str, params: list[Any] | None = None) -> dict:
        """执行 INSERT，返回影响行数 / 主键。"""
        return tool.insert(sql_template, params or []).to_dict()

    @mcp.tool()
    def update(sql_template: str, params: list[Any] | None = None) -> dict:
        """执行 UPDATE，返回影响行数。"""
        return tool.update(sql_template, params or []).to_dict()

    @mcp.tool()
    def delete(sql_template: str, params: list[Any] | None = None) -> dict:
        """执行 DELETE，返回影响行数。"""
        return tool.delete(sql_template, params or []).to_dict()

    @mcp.tool()
    def ping() -> dict:
        """数据库健康检查。"""
        return tool.ping().to_dict()

    @mcp.tool()
    def import_schema(tables: list[str] | None = None) -> dict:
        """导入/反射已有表结构。"""
        return tool.import_schema(tables).to_dict()

    @mcp.tool()
    def refresh_schema() -> dict:
        """刷新表结构缓存。"""
        return tool.refresh_schema().to_dict()

    return mcp


def main():
    """MCP 服务入口（pyproject.scripts 注册）。"""
    config_path = sys.argv[1] if len(sys.argv) > 1 else None
    server = create_server(config_path)
    server.run(transport="stdio")


if __name__ == "__main__":
    main()
