"""Repository 工厂 —— 按 config.DB_TYPE 选 DB 接入层, 操作层/消费器/API 零改动。

新增数据库 = 写一个 ``XxxTraceRepository`` + 此处登记一行。工厂同步返回未启动实例
(连接池随 FastAPI app 生命周期: startup 调 ``await repo.start()``, shutdown ``stop()``)。

配置来源优先级: config 对象属性 > 环境变量 (ADAPTER_DB_TYPE / ADAPTER_PG_*) > 默认值。
"""

from __future__ import annotations

import os
from typing import Any

from agent_adapter.repository.base import TraceRepository
from agent_adapter.repository.postgres import PostgresTraceRepository


def _cfg(config: Any, attr: str, env: str, default: str | None = None) -> str | None:
    """config 属性 (非空) > 环境变量 (非空) > 默认。getattr 带默认值, config=None 安全。"""
    val = getattr(config, attr, None)
    if val not in (None, ""):
        return str(val)
    env_val = os.environ.get(env)
    if env_val not in (None, ""):
        return env_val
    return default


def make_repository(config: Any = None) -> TraceRepository:
    """按 DB_TYPE 选接入层, 返回未启动实例。

    Args:
        config: 任意带 ``db_type``/``pg_*`` 属性的对象 (如 AdapterConfig); None 则全走环境变量。
    """
    db_type = _cfg(config, "db_type", "ADAPTER_DB_TYPE", "postgres")
    if db_type == "postgres":
        host = _cfg(config, "pg_host", "ADAPTER_PG_HOST", "127.0.0.1")
        port = _cfg(config, "pg_port", "ADAPTER_PG_PORT", "5432")
        db = _cfg(config, "pg_db", "ADAPTER_PG_DB", "agent_adapter")
        user = _cfg(config, "pg_user", "ADAPTER_PG_USER", "postgres")
        password = _cfg(config, "pg_password", "ADAPTER_PG_PASSWORD", "")
        dsn = f"postgres://{user}:{password}@{host}:{port}/{db}"
        return PostgresTraceRepository(dsn)
    raise ValueError(f"不支持的 DB_TYPE={db_type!r} (当前仅支持 postgres)")
