"""db-connector MCP 服务入口。

将 db-connector 工具暴露为标准 MCP 服务，支持 stdio / sse / streamable-http 传输。

用法：
    python server.py <config.yaml> [--transport stdio|sse|streamable-http] \
        [--host 0.0.0.0] [--port 8080] [--path /db-connector-server]

部署后服务地址示例：
    http://100.100.135.219:7087/db-connector-server
"""

from __future__ import annotations

import os
import sys
from pathlib import Path


def main():
    """MCP 服务入口。"""
    import argparse

    parser = argparse.ArgumentParser(description="db-connector MCP 服务")
    parser.add_argument("config", nargs="?", default=None, help="配置文件路径")
    parser.add_argument(
        "--transport",
        default="stdio",
        choices=["stdio", "sse", "streamable-http"],
        help="传输方式（默认 stdio）",
    )
    parser.add_argument("--host", default="0.0.0.0", help="监听地址（默认 0.0.0.0）")
    parser.add_argument("--port", type=int, default=8080, help="监听端口（默认 8080）")
    parser.add_argument(
        "--path",
        default="/db-connector-server",
        help="服务路径前缀（默认 /db-connector-server，仅 sse/streamable-http 生效）",
    )
    args = parser.parse_args()

    # 将 db-connector 的 src 加入路径（非 pip install 时）
    tools_src = Path(__file__).resolve().parent.parent.parent / "tools" / "db-connector" / "src"
    if tools_src.exists() and str(tools_src) not in sys.path:
        sys.path.insert(0, str(tools_src))

    from openjiuwen.tools.db_connector.config import load_config
    from openjiuwen.tools.db_connector.tool import DefaultDbConnectorTool, QueryOptions

    try:
        from mcp.server.fastmcp import FastMCP
    except ImportError:
        print("错误：需安装 mcp SDK — pip install mcp", file=sys.stderr)
        sys.exit(1)

    config = load_config(args.config)
    tool = DefaultDbConnectorTool(config)

    mcp = FastMCP(config.mcp.name)

    @mcp.tool()
    def query(
        sql_template: str,
        params: list | None = None,
        max_rows: int | None = None,
    ) -> dict:
        """执行参数化查询，返回结构化结果集。"""
        opts = QueryOptions(max_rows=max_rows) if max_rows else None
        return tool.query(sql_template, params or [], opts).to_dict()

    @mcp.tool()
    def insert(sql_template: str, params: list | None = None) -> dict:
        """执行 INSERT，返回影响行数 / 主键。"""
        return tool.insert(sql_template, params or []).to_dict()

    @mcp.tool()
    def update(sql_template: str, params: list | None = None) -> dict:
        """执行 UPDATE，返回影响行数。"""
        return tool.update(sql_template, params or []).to_dict()

    @mcp.tool()
    def delete(sql_template: str, params: list | None = None) -> dict:
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

    # 启动日志
    print("db-connector MCP 服务启动", file=sys.stderr)
    print(f"  传输方式: {args.transport}", file=sys.stderr)
    print(f"  数据库: {config.data_source.type} @ {config.data_source.host}", file=sys.stderr)
    print(f"  模式: {config.mode}", file=sys.stderr)
    if args.transport == "stdio":
        print("  地址: stdio（本地进程通信）", file=sys.stderr)
    else:
        display_host = args.host if args.host != "0.0.0.0" else os.environ.get("MCP_HOST", "<本机IP>")
        print(f"  地址: http://{display_host}:{args.port}{args.path}", file=sys.stderr)

    if args.transport == "stdio":
        mcp.run(transport="stdio")
    else:
        mcp.run(transport=args.transport, host=args.host, port=args.port, path=args.path)


if __name__ == "__main__":
    main()
