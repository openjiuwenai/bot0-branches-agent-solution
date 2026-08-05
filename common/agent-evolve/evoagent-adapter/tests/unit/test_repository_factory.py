"""repository.factory 单测 —— DB_TYPE 路由, 不连库 (同步, 返回未启动实例)。"""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from agent_adapter.repository.factory import make_repository
from agent_adapter.repository.postgres import PostgresTraceRepository


def _pg_config(**over) -> SimpleNamespace:
    base = dict(db_type="postgres", pg_host="h", pg_port=5432, pg_db="d", pg_user="u", pg_password="p")
    base.update(over)
    return SimpleNamespace(**base)


def test_make_repository_postgres_returns_pg_adapter():
    repo = make_repository(_pg_config())
    assert isinstance(repo, PostgresTraceRepository)
    assert repo.dsn == "postgres://u:p@h:5432/d"
    assert repo.pool is None  # 未启动


def test_make_repository_unknown_type_raises():
    with pytest.raises(ValueError, match="DB_TYPE"):
        make_repository(_pg_config(db_type="mysql"))


def test_make_repository_defaults_to_postgres(monkeypatch):
    monkeypatch.delenv("ADAPTER_DB_TYPE", raising=False)
    # 缺 db_type 字段 → 默认 postgres, 连接参数走环境变量
    monkeypatch.setenv("ADAPTER_PG_HOST", "eh")
    monkeypatch.setenv("ADAPTER_PG_PORT", "5433")
    monkeypatch.setenv("ADAPTER_PG_DB", "edb")
    monkeypatch.setenv("ADAPTER_PG_USER", "eu")
    monkeypatch.setenv("ADAPTER_PG_PASSWORD", "ep")
    repo = make_repository(SimpleNamespace())  # 无任何 db 字段
    assert isinstance(repo, PostgresTraceRepository)
    assert repo.dsn == "postgres://eu:ep@eh:5433/edb"
