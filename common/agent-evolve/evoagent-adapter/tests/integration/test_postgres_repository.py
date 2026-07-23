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
              "start_time", "end_time", "status_code", "status_message",
              "service_name", "scope_name", "scope_version", "session_id"):
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
    for k in ("trace_id", "session_id", "root_span_id", "service_name",
              "span_count", "status"):
        assert row[k] == expected[k], f"traces.{k}: {row[k]!r} != {expected[k]!r}"
    assert row["start_time"].isoformat() == expected["start_time"]
    assert row["end_time"].isoformat() == expected["end_time"]
    # request_summary / response_summary (jsonb; 决策 A 后 response_summary 取自 chain, 可能 None)
    if expected["request_summary"] is not None:
        assert json.loads(row["request_summary"]) == expected["request_summary"]
    else:
        assert row["request_summary"] is None
    if expected["response_summary"] is not None:
        assert json.loads(row["response_summary"]) == expected["response_summary"]
    else:
        assert row["response_summary"] is None


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


# ---- get_spans_by_session ----

async def test_get_spans_by_session(repo, jsonl_spans):
    await repo.bulk_insert_spans(jsonl_spans)
    sid = jsonl_spans[0]["session_id"]
    # 回填后 get_spans_by_session 返回该 session 所属全部 trace 的所有 span
    # (含原空 session、经 A/B 回填的 span), 故期望 = 同 session 的全部 trace 的 span
    trace_session = {}
    for s in jsonl_spans:
        if s.get("session_id") and s["trace_id"] not in trace_session:
            trace_session[s["trace_id"]] = s["session_id"]
    expected = sorted([s for s in jsonl_spans if trace_session.get(s["trace_id"]) == sid],
                      key=lambda x: x["start_time"])
    got = await repo.get_spans_by_session(sid)
    assert [g["span_id"] for g in got] == [s["span_id"] for s in expected]


# ---- get_root_span ----

async def test_get_root_span_returns_server_root(repo, jsonl_spans):
    await repo.bulk_insert_spans(jsonl_spans)
    sid = jsonl_spans[0]["session_id"]
    expected_root = next(s for s in jsonl_spans if s["session_id"] == sid and is_root_span(s))
    root = await repo.get_root_span(sid)
    assert root is not None
    assert root["span_id"] == expected_root["span_id"]
    assert root["kind"] == "SERVER"


async def test_get_root_span_none_when_no_root(repo, jsonl_spans):
    no_root = [{**s, "kind": "INTERNAL"} for s in jsonl_spans]  # 抹掉 SERVER
    await repo.bulk_insert_spans(no_root)
    sid = no_root[0]["session_id"]
    assert await repo.get_root_span(sid) is None


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


# ---- list_sessions ----

async def test_list_sessions(repo, jsonl_spans):
    await repo.bulk_insert_spans(jsonl_spans)
    convs = await repo.list_sessions()
    traces = _by_trace(jsonl_spans)
    assert {c["session_id"] for c in convs} == {
        t[0]["session_id"] for t in traces.values()
    }
    svc = jsonl_spans[0]["service_name"]
    filtered = await repo.list_sessions(agent_name=svc)
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
    assert row["session_id"] == summary["session_id"]


# ---- session_id 回填 (A 批内 + B 跨批兜底 + 全表维护) ----

async def _null_session_count(repo) -> int:
    async with repo.pool.acquire() as conn:
        return await conn.fetchval("SELECT count(*) FROM spans WHERE session_id IS NULL")


def _pick_mixed_trace(spans):
    """选一条同时含 session span 与空 session span 的 trace (动态, 不写死 id)。"""
    for tid, ts in _by_trace(spans).items():
        sess = [s for s in ts if s.get("session_id")]
        empty = [s for s in ts if not s.get("session_id")]
        if sess and empty:
            return tid, sess, empty
    return None, [], []


async def test_bulk_insert_backfills_session_in_batch(repo, jsonl_spans):
    """A: bulk_insert 整批后, 非孤儿 trace 的空 session span 被同 trace 兄弟回填;
    孤儿 trace (整 trace 无 session) 的 span 保留 NULL (不造值)。"""
    spans = [dict(s) for s in jsonl_spans]  # 拷贝: A 原地改 session_id, 防污染共享 fixture
    await repo.bulk_insert_spans(spans)
    orphan_tids = {tid for tid, ts in _by_trace(spans).items()
                   if not any(s.get("session_id") for s in ts)}
    # DB 内空 session 行 == 孤儿 trace 的全部 span (非孤儿已被 A 回填)
    assert await _null_session_count(repo) == sum(
        len(ts) for tid, ts in _by_trace(spans).items() if tid in orphan_tids)
    # 同 trace session 唯一: 非孤儿全等于该 trace 的 session; 孤儿全 NULL
    async with repo.pool.acquire() as conn:
        rows = await conn.fetch("SELECT trace_id, session_id FROM spans")
    by_t: dict[str, set] = {}
    for r in rows:
        by_t.setdefault(r["trace_id"], set()).add(r["session_id"])
    for tid, ts in _by_trace(spans).items():
        if tid in orphan_tids:
            assert by_t[tid] == {None}, f"孤儿 trace {tid} 应全 NULL: {by_t[tid]}"
        else:
            sess = next(s["session_id"] for s in ts if s.get("session_id"))
            assert by_t[tid] == {sess}, f"trace {tid} session 不唯一/未回填: {by_t[tid]}"


async def test_bulk_insert_backfills_cross_batch_straggler(repo, jsonl_spans):
    """B: 空 session span 的兄弟在前一批已入库时, 本批 in-memory 回填 (A) 看不到,
    由 per-trace SQL 兜底 (_backfill_session_id_for_trace) 回填。"""
    tid, sess_spans, empty_spans = _pick_mixed_trace(jsonl_spans)
    assert tid, "jsonl 无同时含 session 与空 session span 的 trace"
    expected_sid = sess_spans[0]["session_id"]
    # 第一批: 只插 session 兄弟 (拷贝防污染 fixture)
    await repo.bulk_insert_spans([dict(s) for s in sess_spans])
    # 第二批: 只插空 span —— A 只看本批 (全空) 回填不了, 靠 B 读 DB 兜底
    await repo.bulk_insert_spans([dict(s) for s in empty_spans])
    for g in await repo.get_spans_by_trace(tid):
        assert g["session_id"] == expected_sid, (
            f"跨批空 span 未被 B 回填: span={g['span_id']} session={g['session_id']!r}")


async def test_backfill_session_id_full_table(repo, jsonl_spans):
    """公开全表回填: 用 insert_span (单条, 不走 bulk 的 A/B) 插入, 空行留 NULL;
    调 backfill_session_id() 后用同 trace 兄弟补齐; 返回受影响行数。"""
    tid, sess_spans, empty_spans = _pick_mixed_trace(jsonl_spans)
    assert tid, "jsonl 无同时含 session 与空 session span 的 trace"
    expected_sid = sess_spans[0]["session_id"]
    # 单条插入 (insert_span 不触发 A/B), 空 span 行留 NULL
    for s in sess_spans + empty_spans:
        await repo.insert_span(dict(s))
    got = await repo.get_spans_by_trace(tid)
    assert any(g["session_id"] is None for g in got), "前置失败: 空 span 未留 NULL"
    n = await repo.backfill_session_id()
    assert n == len(empty_spans), f"回填行数 {n} != 空 span 数 {len(empty_spans)}"
    for g in await repo.get_spans_by_trace(tid):
        assert g["session_id"] == expected_sid, f"全表回填后仍有空/错值: {g['session_id']!r}"

