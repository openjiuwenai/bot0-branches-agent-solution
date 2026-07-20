"""PostgresTraceRepository 集成测试 —— 真 asyncpg + 真 PG (独立 agent_adapter_test 库)。

钉住 I/O 层: 连接池生命周期、init_schema、span 往返 (JSONB 保真)、traces 汇总聚合
(由 repository.aggregation 计算)、各查询方法的排序与过滤。聚合逻辑本身由
tests/unit/test_aggregation.py 覆盖; 此处验证 I/O 接线与 SQL 方言 (JSONB/timestamptz)。

PG fixtures (test_db / repo / jsonl_spans) 见 tests/integration/conftest.py。
"""

from __future__ import annotations

import json

from agent_adapter.repository.aggregation import compute_trace_summary, is_root_span
from tests.integration import _pgutil

_by_trace = _pgutil.by_trace


# ---- init_schema ----

async def test_init_schema_creates_tables(repo):
    async with repo.pool.acquire() as conn:
        rows = await conn.fetch(
            "SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename"
        )
    names = [r["tablename"] for r in rows]
    assert "spans" in names
    assert "traces" in names


# ---- insert_span + get_spans_by_trace (JSONB 往返) ----

async def test_insert_span_roundtrips_jsonb(repo, jsonl_spans):
    s = jsonl_spans[0]
    await repo.insert_span(s)
    got = await repo.get_spans_by_trace(s["trace_id"])
    assert len(got) == 1
    g = got[0]
    for k in ("trace_id", "span_id", "parent_span_id", "name", "kind",
              "start_time", "end_time", "duration_ns", "status_code", "status_message",
              "service_name", "scope_name", "scope_version", "conversation_id"):
        assert g[k] == s.get(k), f"{k}: {g[k]!r} != {s.get(k)!r}"
    assert g["attributes"] == s.get("attributes")
    assert g["resource_attributes"] == s.get("resource_attributes")


async def test_get_spans_by_trace_ordered_by_start_time(repo, jsonl_spans):
    trace_id, tspans = next(iter(_by_trace(jsonl_spans).items()))
    for s in reversed(tspans):  # 乱序插入, 查询应按 start_time 升序
        await repo.insert_span(s)
    got = await repo.get_spans_by_trace(trace_id)
    assert [g["span_id"] for g in got] == [s["span_id"] for s in sorted(tspans, key=lambda x: x["start_time"])]


# ---- traces 汇总聚合 ----

async def test_insert_span_upserts_trace_summary(repo, jsonl_spans):
    trace_id, tspans = next(iter(_by_trace(jsonl_spans).items()))
    for s in tspans:
        await repo.insert_span(s)
    expected = compute_trace_summary(trace_id, tspans)
    async with repo.pool.acquire() as conn:
        row = await conn.fetchrow("SELECT * FROM traces WHERE trace_id=$1", trace_id)
    assert row is not None
    for k in ("trace_id", "conversation_id", "root_span_id", "service_name",
              "span_count", "status", "openjiuwen_trace_id", "user_id"):
        assert row[k] == expected[k], f"traces.{k}: {row[k]!r} != {expected[k]!r}"
    assert row["start_time"].isoformat() == expected["start_time"]
    assert row["end_time"].isoformat() == expected["end_time"]
    assert json.loads(row["request_summary"]) == expected["request_summary"]
    assert json.loads(row["response_summary"]) == expected["response_summary"]


async def test_insert_span_idempotent_span_count(repo, jsonl_spans):
    """重复插同一 span 不重复计数 (spans ON CONFLICT + 汇总重算)。"""
    trace_id, tspans = next(iter(_by_trace(jsonl_spans).items()))
    for s in tspans:
        await repo.insert_span(s)
    await repo.insert_span(tspans[0])  # 再插一遍第一个 span
    async with repo.pool.acquire() as conn:
        cnt = await conn.fetchval("SELECT span_count FROM traces WHERE trace_id=$1", trace_id)
        span_rows = await conn.fetchval("SELECT count(*) FROM spans WHERE trace_id=$1", trace_id)
    assert cnt == len(tspans)
    assert span_rows == len(tspans)


# ---- bulk_insert_spans ----

async def test_bulk_insert_spans_all_traces(repo, jsonl_spans):
    await repo.bulk_insert_spans(jsonl_spans)
    traces = _by_trace(jsonl_spans)
    async with repo.pool.acquire() as conn:
        rows = {r["trace_id"]: r for r in await conn.fetch("SELECT * FROM traces")}
    assert set(rows) == set(traces)
    for trace_id, tspans in traces.items():
        expected = compute_trace_summary(trace_id, tspans)
        assert rows[trace_id]["span_count"] == expected["span_count"]
        assert rows[trace_id]["status"] == expected["status"]
        assert rows[trace_id]["root_span_id"] == expected["root_span_id"]


# ---- get_spans_by_conversation ----

async def test_get_spans_by_conversation(repo, jsonl_spans):
    await repo.bulk_insert_spans(jsonl_spans)
    conv = jsonl_spans[0]["conversation_id"]
    expected = sorted([s for s in jsonl_spans if s["conversation_id"] == conv],
                      key=lambda x: x["start_time"])
    got = await repo.get_spans_by_conversation(conv)
    assert [g["span_id"] for g in got] == [s["span_id"] for s in expected]


# ---- get_root_span ----

async def test_get_root_span_returns_server_root(repo, jsonl_spans):
    await repo.bulk_insert_spans(jsonl_spans)
    conv = jsonl_spans[0]["conversation_id"]
    expected_root = next(s for s in jsonl_spans if s["conversation_id"] == conv and is_root_span(s))
    root = await repo.get_root_span(conv)
    assert root is not None
    assert root["span_id"] == expected_root["span_id"]
    assert root["kind"] == "SERVER"


async def test_get_root_span_none_when_no_root(repo, jsonl_spans):
    no_root = [{**s, "kind": "INTERNAL"} for s in jsonl_spans]  # 抹掉 SERVER
    await repo.bulk_insert_spans(no_root)
    conv = no_root[0]["conversation_id"]
    assert await repo.get_root_span(conv) is None


# ---- get_trace_tree ----

async def test_get_trace_tree(repo, jsonl_spans):
    await repo.bulk_insert_spans(jsonl_spans)
    trace_id, tspans = next(iter(_by_trace(jsonl_spans).items()))
    tree = await repo.get_trace_tree(trace_id)
    assert tree is not None
    expected_root = next(s for s in tspans if is_root_span(s))
    assert tree["span_id"] == expected_root["span_id"]
    seen: list[str] = []

    def walk(node: dict) -> None:
        seen.append(node["span_id"])
        for c in node.get("children", []):
            walk(c)

    walk(tree)
    assert sorted(seen) == sorted(s["span_id"] for s in tspans)


# ---- list_conversations ----

async def test_list_conversations(repo, jsonl_spans):
    await repo.bulk_insert_spans(jsonl_spans)
    convs = await repo.list_conversations()
    traces = _by_trace(jsonl_spans)
    assert {c["conversation_id"] for c in convs} == {
        t[0]["conversation_id"] for t in traces.values()
    }
    svc = jsonl_spans[0]["service_name"]
    filtered = await repo.list_conversations(agent_name=svc)
    assert all(c["service_name"] == svc for c in filtered)
    assert len(filtered) == sum(1 for t in traces.values() if t[0]["service_name"] == svc)


# ---- upsert_trace (显式汇总) ----

async def test_upsert_trace_explicit(repo, jsonl_spans):
    trace_id, tspans = next(iter(_by_trace(jsonl_spans).items()))
    summary = compute_trace_summary(trace_id, tspans)
    await repo.upsert_trace(summary)
    async with repo.pool.acquire() as conn:
        row = await conn.fetchrow("SELECT * FROM traces WHERE trace_id=$1", trace_id)
    assert row is not None
    assert row["span_count"] == summary["span_count"]
    assert row["conversation_id"] == summary["conversation_id"]
