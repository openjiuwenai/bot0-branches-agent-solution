"""集成测试共享 fixtures: PG 不可达整目录 skip + session 级测试库 + 函数级干净 repo。"""

from __future__ import annotations

import asyncio

import asyncpg
import pytest
import pytest_asyncio

from agent_adapter.repository.postgres import PostgresTraceRepository
from tests.integration import _pgutil


@pytest.fixture(scope="session", autouse=True)
def _skip_if_no_pg():
    """PG 不可达时跳过本目录全部集成测试。"""
    if not _pgutil.pg_available():
        pytest.skip("PG 不可达, 跳过集成测试")


@pytest.fixture(scope="session")
def test_db():
    """session 级: 建独立测试库一次, 全部用例共享; 跑完删除。"""
    async def _create():
        c = await asyncpg.connect(_pgutil.dsn(_pgutil.MAINT_DB))
        await c.execute(f"DROP DATABASE IF EXISTS {_pgutil.TEST_DB} WITH (FORCE)")
        await c.execute(f"CREATE DATABASE {_pgutil.TEST_DB}")
        await c.close()

    async def _drop():
        c = await asyncpg.connect(_pgutil.dsn(_pgutil.MAINT_DB))
        await c.execute(f"DROP DATABASE IF EXISTS {_pgutil.TEST_DB} WITH (FORCE)")
        await c.close()

    asyncio.run(_create())
    yield _pgutil.TEST_DB
    asyncio.run(_drop())


@pytest_asyncio.fixture
async def repo(test_db):
    """每个用例一个干净 repo: 启动连接池 + init_schema + 清空表, 用完停止。"""
    r = PostgresTraceRepository(_pgutil.dsn(_pgutil.TEST_DB))
    await r.start()
    await r.init_schema()
    async with r.pool.acquire() as conn:
        await conn.execute("TRUNCATE spans, traces")
    yield r
    await r.stop()


@pytest.fixture(scope="session")
def synthetic_spans() -> list[dict]:
    """session 级生成合成 spans 一次（只读；用例需改动请拷贝）。"""
    return _pgutil.load_spans()
