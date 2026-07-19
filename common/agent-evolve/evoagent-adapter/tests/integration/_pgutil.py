"""集成测试共享工具: PG 连接配置 + jsonl 加载 + 分组 helper。

被 conftest.py (fixtures) 与各集成测试模块 (skipif + 数据派生断言) 复用。
"""

from __future__ import annotations

import asyncio
import json
import os
from pathlib import Path

import asyncpg

PG_HOST = os.environ.get("ADAPTER_TEST_PG_HOST", "127.0.0.1")
PG_PORT = int(os.environ.get("ADAPTER_TEST_PG_PORT", "5432"))
PG_USER = os.environ.get("ADAPTER_TEST_PG_USER", "otel_user")
PG_PASSWORD = os.environ.get("ADAPTER_TEST_PG_PASSWORD", "otel_password")
MAINT_DB = os.environ.get("ADAPTER_TEST_PG_MAINT_DB", "otel_db")
TEST_DB = os.environ.get("ADAPTER_TEST_PG_DB", "agent_adapter_test")

_JSONL_REL = Path("mock-assets") / "scripts" / "deployment" / "temp" / "otel_spans_v2.jsonl"


def dsn(db: str) -> str:
    return f"postgres://{PG_USER}:{PG_PASSWORD}@{PG_HOST}:{PG_PORT}/{db}"


def pg_available() -> bool:
    try:
        asyncio.run(asyncpg.connect(dsn(MAINT_DB)))
        return True
    except Exception:
        return False


def find_jsonl() -> Path:
    here = Path(__file__).resolve()
    for parent in [here.parent, *here.parents]:
        cand = parent / _JSONL_REL
        if cand.exists():
            return cand
    raise AssertionError(f"otel_spans_v2.jsonl 未找到 (从 {here} 向上)")


def load_jsonl_spans() -> list[dict]:
    """加载 jsonl 并提升 service_name/conversation_id (对齐 parse_span 输出形状)。"""
    raw = find_jsonl().read_text(encoding="utf-8")
    dec = json.JSONDecoder()
    i, n, spans = 0, len(raw), []
    while i < n:
        while i < n and raw[i].isspace():
            i += 1
        if i >= n:
            break
        obj, end = dec.raw_decode(raw, i)
        spans.append(obj)
        i = end
    for s in spans:
        s.setdefault("service_name", (s.get("resource_attributes") or {}).get("service.name"))
        s.setdefault("conversation_id", (s.get("attributes") or {}).get("session.id"))
    return spans


def by_trace(spans: list[dict]) -> dict[str, list[dict]]:
    groups: dict[str, list[dict]] = {}
    for s in spans:
        groups.setdefault(s["trace_id"], []).append(s)
    return groups
