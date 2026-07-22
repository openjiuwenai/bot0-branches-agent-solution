"""DbTraceSource 端到端集成测试 —— 设计文档点 7 (数据一致性) de-risk。

PG spans → DbTraceSource.get_records (spans_to_records) → clean_traces,
验证 standard 模式对真实 jsonl 数据产出与 log 模式等价的 cleaned 结果
(task_input == http 根 request_body.input.query, messages 非空, 末条 assistant)。

PG fixtures (test_db / repo / jsonl_spans) 见 tests/integration/conftest.py。
"""

from __future__ import annotations

import json

from agent_adapter.trace_cleaner import clean_traces
from agent_adapter.trace_source.db_source import DbTraceSource
from agent_adapter.trace_source.spans_to_records import spans_to_records
from tests.integration import _pgutil

_by_trace = _pgutil.by_trace


async def test_db_trace_source_cleaned_matches_user_query(repo, jsonl_spans):
    """端到端: PG spans → records → clean_traces, task_input == http 根 request_body.input.query。"""
    await repo.bulk_insert_spans(jsonl_spans)
    src = DbTraceSource(repo)

    for trace_id, tspans in _by_trace(jsonl_spans).items():
        root = next((s for s in tspans if s.get("kind") == "SERVER" and not s.get("parent_span_id")), None)
        if root is None:
            continue  # V3 部分 trace 无 http 根
        if not any((s.get("name") or "").startswith("llm.") for s in tspans):
            continue  # 无 llm → 无 GENERATION → clean_traces 空, 跳过
        rb_raw = (root.get("attributes") or {}).get("openjiuwen.http.request_body")
        request_body = json.loads(rb_raw) if isinstance(rb_raw, str) else rb_raw
        user_query = request_body["input"]["query"]  # V3 真实: input.query (非 user_query)

        records = await src.get_records(agent_name=None, conversation_id=root["session_id"])
        cleaned = clean_traces(records, session_id=root["session_id"], agent_name="edp_agent")

        assert cleaned != {}, f"trace {trace_id}: 无 GENERATION → clean_traces 空"
        assert cleaned["task_input"] == user_query, f"trace {trace_id}: task_input != query"
        assert len(cleaned["messages"]) > 0
        assert cleaned["messages"][-1]["role"] == "assistant"


async def test_db_trace_source_list_conversations(repo, jsonl_spans):
    await repo.bulk_insert_spans(jsonl_spans)
    src = DbTraceSource(repo)
    convs = await src.list_conversations()  # TraceSource 接口层 (桥接 repo.list_sessions)
    expected = {t[0]["session_id"] for t in _by_trace(jsonl_spans).values()}
    assert set(convs) == expected
    assert len(convs) == len(expected)  # 去重


async def test_db_trace_source_get_records_matches_converter(repo, jsonl_spans):
    """get_records 等价于 spans_to_records(repo.get_spans_by_session) (接线校验)。"""
    await repo.bulk_insert_spans(jsonl_spans)
    src = DbTraceSource(repo)
    sid = jsonl_spans[0]["session_id"]
    via_src = await src.get_records(agent_name=None, conversation_id=sid)
    spans = await repo.get_spans_by_session(sid)
    assert via_src == spans_to_records(spans)
