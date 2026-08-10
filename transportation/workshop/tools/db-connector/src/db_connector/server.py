"""db-connector 独立 HTTP 服务（FastAPI）。

将 DbConnectorTool 暴露为 RESTful HTTP 服务，支持 GET / POST 调用，
适合作为独立数据访问微服务部署。

用法：
    python3 -m db_connector.server config/config.yaml
    python3 -m db_connector.server config/config.yaml \\
        --host 0.0.0.0 --port 7087 --path /db-connector

部署后服务地址示例：
    http://100.100.135.219:7087/db-connector

首次使用流程：
    1. POST /config 提交真实数据库信息（host/user/password/...）
    2. GET  /ping 验证连通
    3. POST /query 等 CRUD 接口可用
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Any

from .config import DbConnectorConfig, load_config
from .tool import DefaultDbConnectorTool, QueryOptions


def _runtime_config_path() -> Path:
    """运行期配置持久化路径（与 start.sh 同目录的 config/config.runtime.yaml）。"""
    here = Path(__file__).resolve()
    # src/db_connector/server.py → 项目根（pyproject 所在）
    proj_root = here.parents[2]
    return proj_root / "config" / "config.runtime.yaml"


def _load_runtime_or_file(config_path: str | None) -> DbConnectorConfig:
    """优先加载 runtime 配置（已通过 /config 接口写入），否则加载传入文件。"""
    rt = _runtime_config_path()
    if rt.exists():
        return load_config(str(rt))
    return load_config(config_path)


def _dump_config(config: DbConnectorConfig) -> None:
    """把当前配置持久化到 config/config.runtime.yaml（明文，不入库）。"""
    import yaml

    rt = _runtime_config_path()
    rt.parent.mkdir(parents=True, exist_ok=True)
    data = {
        "db-connector": config.model_dump(by_alias=False)
    }
    rt.write_text(yaml.safe_dump(data, allow_unicode=True), encoding="utf-8")


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

    config = _load_runtime_or_file(config_path)
    tool = DefaultDbConnectorTool(config)

    app = FastAPI(
        title="db-connector",
        description="数据库连接 Agent 工具 — HTTP 服务",
        version="0.1.0",
    )

    # ------------------------------------------------------------------
    # 请求模型
    # ------------------------------------------------------------------

    class DbConfigRequest(BaseModel):
        """提交数据库配置（首次使用或切换数据源时调用）。"""
        type: str = Field(default="mysql", description="数据库类型：mysql | postgresql")
        host: str = Field(..., description="数据库主机")
        port: int = Field(default=3306, description="端口（MySQL 3306 / PostgreSQL 5432）")
        database: str = Field(..., description="数据库名")
        username: str = Field(..., description="数据库用户名")
        password: str = Field(..., description="数据库密码（明文传入，服务端不回显）")
        schema: str = Field(default="public", description="模式名（PostgreSQL 用）")
        mode: str = Field(default="readonly", description="运行模式：readonly | readwrite | ddl")
        allowed_tables: Optional[list[str]] = Field(
            default=None, description="允许访问的表白名单；缺省不限制"
        )

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
    # 配置接口（首次使用入口）
    # ------------------------------------------------------------------

    @app.get("/config", summary="查看当前数据库配置（密码已脱敏）")
    def get_config() -> dict:
        """返回当前 data_source 配置，密码字段已脱敏。"""
        ds = tool._config.data_source
        return {
            "configured": tool.is_configured(),
            "type": ds.type,
            "host": ds.host,
            "port": ds.port,
            "database": ds.database,
            "username": ds.username,
            "password": "***" if ds.password else "",
            "schema": ds.schema,
            "mode": tool._config.mode,
            "allowed_tables": tool._config.security.allowed_tables,
        }

    @app.post("/config", summary="提交/更新数据库配置")
    def set_config(req: DbConfigRequest) -> dict:
        """提交真实数据库信息，服务即时切换数据源并持久化配置。

        - 切换前先 dispose 旧连接池
        - 配置写入 config/config.runtime.yaml，重启后自动加载
        - 提交成功后建议立即 GET /ping 验证连通
        """
        from .config import (
            DataSourceConfig, PoolConfig, CredentialConfig,
            SecurityConfig, AuditConfig, SchemaImportConfig, McpConfig,
        )

        # 构建新配置（保留原 audit/schema_import 等非 DB 部分）
        old = tool._config
        allowed = req.allowed_tables if req.allowed_tables is not None \
            else old.security.allowed_tables
        new_cfg = DbConnectorConfig(
            enabled=True,
            mode=req.mode,
            data_source=DataSourceConfig(
                type=req.type, host=req.host, port=req.port,
                database=req.database, username=req.username,
                password=req.password, schema=req.schema,
                pool=old.data_source.pool or PoolConfig(),
            ),
            credential=CredentialConfig(provider="plain"),
            schema_import=old.schema_import,
            security=SecurityConfig(
                allowed_tables=allowed,
                blocked_keywords=old.security.blocked_keywords,
                max_rows=old.security.max_rows,
                query_timeout_ms=old.security.query_timeout_ms,
                allow_ddl=old.security.allow_ddl,
                sql_template_whitelist=old.security.sql_template_whitelist,
            ),
            audit=old.audit,
            mcp=old.mcp,
        )

        try:
            tool.reconfigure(new_cfg)
            _dump_config(new_cfg)
        except Exception as e:
            raise HTTPException(status_code=500, detail=f"配置失败: {e}")

        return {
            "status": "ok",
            "message": "配置已更新并持久化，建议 GET /ping 验证连通",
            "configured": True,
            "host": req.host,
            "database": req.database,
        }

    # ------------------------------------------------------------------
    # 业务接口
    # ------------------------------------------------------------------

    @app.get("/ping", summary="数据库健康检查")
    def ping() -> dict:
        """探测数据库连通性与延迟。未配置时返回 status=unconfigured。"""
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
        except RuntimeError as e:
            raise HTTPException(status_code=428, detail=str(e))
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        except Exception as e:
            raise HTTPException(status_code=500, detail=str(e))

    @app.post("/insert", summary="执行参数化插入")
    def insert(req: WriteRequest) -> dict:
        """执行 INSERT，返回影响行数 / 主键。"""
        try:
            return tool.insert(req.sql_template, req.params or []).to_dict()
        except RuntimeError as e:
            raise HTTPException(status_code=428, detail=str(e))
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        except Exception as e:
            raise HTTPException(status_code=500, detail=str(e))

    @app.post("/update", summary="执行参数化更新")
    def update(req: WriteRequest) -> dict:
        """执行 UPDATE，返回影响行数。"""
        try:
            return tool.update(req.sql_template, req.params or []).to_dict()
        except RuntimeError as e:
            raise HTTPException(status_code=428, detail=str(e))
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        except Exception as e:
            raise HTTPException(status_code=500, detail=str(e))

    @app.post("/delete", summary="执行参数化删除")
    def delete(req: WriteRequest) -> dict:
        """执行 DELETE，返回影响行数。"""
        try:
            return tool.delete(req.sql_template, req.params or []).to_dict()
        except RuntimeError as e:
            raise HTTPException(status_code=428, detail=str(e))
        except ValueError as e:
            raise HTTPException(status_code=400, detail=str(e))
        except Exception as e:
            raise HTTPException(status_code=500, detail=str(e))

    @app.post("/import-schema", summary="导入/反射已有表结构")
    def import_schema(req: ImportSchemaRequest) -> dict:
        """反射客户已有表结构，生成字段映射快照。"""
        try:
            return tool.import_schema(req.tables).to_dict()
        except RuntimeError as e:
            raise HTTPException(status_code=428, detail=str(e))
        except Exception as e:
            raise HTTPException(status_code=500, detail=str(e))

    @app.post("/refresh-schema", summary="刷新表结构缓存")
    def refresh_schema() -> dict:
        """重新反射并覆盖快照。"""
        try:
            return tool.refresh_schema().to_dict()
        except RuntimeError as e:
            raise HTTPException(status_code=428, detail=str(e))
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
