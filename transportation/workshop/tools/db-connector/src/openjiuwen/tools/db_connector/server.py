"""db-connector 独立 HTTP 服务（FastAPI）。

将 DbConnectorTool 暴露为 RESTful HTTP 服务，支持 GET / POST 调用，
适合作为独立数据访问微服务部署。

用法：
    python3 -m openjiuwen.tools.db_connector.server config/config.yaml
    python3 -m openjiuwen.tools.db_connector.server config/config.yaml \\
        --host 0.0.0.0 --port 7087 --path /db-connector

部署后服务地址示例：
    http://100.100.135.219:7087/db-connector
"""

from __future__ import annotations

import os
import sys
from typing import Any

from .config import load_config
from .tool import DefaultDbConnectorTool, QueryOptions


def create_app(config_path: str | None = None):
    """创建 FastAPI 应用并注册路由。

    返回 (app, tool)，调用方可进一步定制。
    """
    try:
        from fastapi import FastAPI, HTTPException
        from pydantic import BaseModel, Field
    except ImportError as e:
        raise RuntimeError(
            "需安装 FastAPI：pip install openjiuwen-db-connector[server]"
        ) from e

    from typing import Optional

    config = load_config(config_path)
    tool = DefaultDbConnectorTool(config)

    app = FastAPI(
        title="db-connector",
        description="数据库连接 Agent 工具 — HTTP 服务",
        version="0.1.0",
    )

    # ------------------------------------------------------------------
    # 请求模型
    # ------------------------------------------------------------------

    class QueryRequest(BaseModel):
        sql_template: str = Field(..., description="带 %s 占位符的 SELECT 语句")
        params: Optional[list[Any]] = Field(default=None, description="占位符参数列表")
        max_rows: Optional[int] = Field(default=None, description="最大返回行数")

    class WriteRequest(BaseModel):
        sql_template: str = Field(..., description="带 %s 占位符的 INSERT/UPDATE/DELETE 语句")
        params: Optional[list[Any]] = Field(default=None, description="占位符参数列表")

    class ImportSchemaRequest(BaseModel):
        tables: Optional[list[str]] = Field(default=None, description="指定表名列表；缺省导入全部白名单表")

    # ------------------------------------------------------------------
    # 路由
    # ------------------------------------------------------------------

    @app.get("/ping", summary="数据库健康检查")
    def ping() -> dict:
        """探测数据库连通性与延迟。"""
        try:
            return tool.ping().to_dict()
        except Exception as e:
            raise HTTPException(status_code=500, detail=str(e))

    @app.post("/query", summary="执行参数化查询")
    def query(req: QueryRequest) -> dict:
        """执行参数化 SELECT，返回结构化结果集。"""
        try:
            opts = QueryOptions(max_rows=req.max_rows) if req.max_rows else None
            return tool.query(req.sql_template, req.params or [], opts).to_dict()
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        except Exception as e:
            raise HTTPException(status_code=500, detail=str(e))

    @app.post("/insert", summary="执行参数化插入")
    def insert(req: WriteRequest) -> dict:
        """执行 INSERT，返回影响行数 / 主键。"""
        try:
            return tool.insert(req.sql_template, req.params or []).to_dict()
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        except Exception as e:
            raise HTTPException(status_code=500, detail=str(e))

    @app.post("/update", summary="执行参数化更新")
    def update(req: WriteRequest) -> dict:
        """执行 UPDATE，返回影响行数。"""
        try:
            return tool.update(req.sql_template, req.params or []).to_dict()
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        except Exception as e:
            raise HTTPException(status_code=500, detail=str(e))

    @app.post("/delete", summary="执行参数化删除")
    def delete(req: WriteRequest) -> dict:
        """执行 DELETE，返回影响行数。"""
        try:
            return tool.delete(req.sql_template, req.params or []).to_dict()
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        except Exception as e:
            raise HTTPException(status_code=500, detail=str(e))

    @app.post("/import-schema", summary="导入/反射已有表结构")
    def import_schema(req: ImportSchemaRequest) -> dict:
        """反射客户已有表结构，生成字段映射快照。"""
        try:
            return tool.import_schema(req.tables).to_dict()
        except Exception as e:
            raise HTTPException(status_code=500, detail=str(e))

    @app.post("/refresh-schema", summary="刷新表结构缓存")
    def refresh_schema() -> dict:
        """重新反射并覆盖快照。"""
        try:
            return tool.refresh_schema().to_dict()
        except Exception as e:
            raise HTTPException(status_code=500, detail=str(e))

    return app, tool


def main():
    """HTTP 服务入口。"""
    import argparse

    parser = argparse.ArgumentParser(description="db-connector HTTP 服务")
    parser.add_argument("config", nargs="?", default=None, help="配置文件路径")
    parser.add_argument("--host", default="0.0.0.0", help="监听地址（默认 0.0.0.0）")
    parser.add_argument("--port", type=int, default=7087, help="监听端口（默认 7087）")
    parser.add_argument(
        "--path",
        default="/db-connector",
        help="服务路径前缀（默认 /db-connector）",
    )
    args = parser.parse_args()

    try:
        import uvicorn
    except ImportError:
        print("错误：需安装 uvicorn — pip install openjiuwen-db-connector[server]", file=sys.stderr)
        sys.exit(1)

    app, tool = create_app(args.config)

    # 启动日志
    print("db-connector HTTP 服务启动", file=sys.stderr)
    print(f"  监听: {args.host}:{args.port}", file=sys.stderr)
    print(f"  路径前缀: {args.path}", file=sys.stderr)
    if args.host != "0.0.0.0":
        print(f"  地址: http://{args.host}:{args.port}{args.path}", file=sys.stderr)
    else:
        display_host = os.environ.get("DB_CONNECTOR_HOST", "<本机IP>")
        print(f"  地址: http://{display_host}:{args.port}{args.path}", file=sys.stderr)
        print(f"  示例: http://100.100.135.219:{args.port}{args.path}", file=sys.stderr)

    uvicorn.run(app, host=args.host, port=args.port, root_path=args.path)


if __name__ == "__main__":
    main()
