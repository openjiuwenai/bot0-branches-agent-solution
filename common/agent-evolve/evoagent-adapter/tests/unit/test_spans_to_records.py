"""spans_to_records 纯转换器单测 —— 设计文档点 7 (数据一致性) 的核心验证。

OTel span (jsonl 模拟 EDPAgent 数据) → log-mode archive record 格式, 使下游
trace_cleaner.clean_traces 零改动。关键映射:
- llm.Model span → GENERATION record (input.messages=parse(gen_ai.prompt).inputs,
  output=parse(gen_ai.completion).output)
- tool.* span    → TOOL record
- http.request SERVER → TRACE record (id=trace_id)

等价验证: spans→records→clean_traces 的 task_input == root span 的 user_query
(已验证 jsonl 3 条 trace 全 MATCH), messages 非空。这是 standard 模式与 log 模式
在 cleaned-traces API 上行为一致的保证。
"""

from __future__ import annotations

import json
from pathlib import Path

from agent_adapter.trace_cleaner import clean_traces
from agent_adapter.trace_source.spans_to_records import spans_to_records

_JSONL_REL = Path("mock-assets") / "scripts" / "deployment" / "temp" / "otel_spans_v2.jsonl"


def _find_jsonl() -> Path:
    here = Path(__file__).resolve()
    for parent in [here.parent, *here.parents]:
        cand = parent / _JSONL_REL
        if cand.exists():
            return cand
    raise AssertionError(f"otel_spans_v2.jsonl 未找到 (从 {here} 向上)")


def _load_jsonl_spans() -> list[dict]:
    raw = _find_jsonl().read_text(encoding="utf-8")
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


def _by_trace(spans: list[dict]) -> dict[str, list[dict]]:
    g: dict[str, list[dict]] = {}
    for s in spans:
        g.setdefault(s["trace_id"], []).append(s)
    return g


# ---- llm.Model → GENERATION ----

def test_llm_model_span_to_generation_record():
    spans = _load_jsonl_spans()
    llm = next(s for s in spans if s.get("name") == "llm.Model")
    recs = spans_to_records([llm])
    assert len(recs) == 1
    rec = recs[0]
    assert rec["type"] == "GENERATION"
    assert rec["id"] == llm["span_id"]
    assert rec["trace_id"] == llm["trace_id"]
    assert rec["session_id"] == llm["conversation_id"]
    # input.messages = parse(gen_ai.prompt).inputs
    attrs = llm.get("attributes") or {}
    expected_msgs = json.loads(attrs["gen_ai.prompt"]).get("inputs", [])
    assert rec["input"]["messages"] == expected_msgs
    # output = parse(gen_ai.completion).output
    expected_out = json.loads(attrs["gen_ai.completion"]).get("output")
    assert rec["output"] == expected_out


def test_spans_to_records_jsonl_all_generations():
    spans = _load_jsonl_spans()
    recs = spans_to_records(spans)
    gens = [r for r in recs if r.get("type") == "GENERATION"]
    assert len(gens) == sum(1 for s in spans if s.get("name") == "llm.Model")


def test_llm_span_missing_prompt_skipped():
    """llm.Model 但缺 gen_ai.prompt/completion → 跳过 (不产残缺 GENERATION)。"""
    spans = _load_jsonl_spans()
    llm = next(s for s in spans if s.get("name") == "llm.Model")
    attrs = {k: v for k, v in (llm.get("attributes") or {}).items()
             if k not in ("gen_ai.prompt", "gen_ai.completion")}
    broken = {**llm, "attributes": attrs}
    recs = spans_to_records([broken])
    assert recs == []


# ---- tool.* → TOOL ----

def test_tool_span_to_tool_record():
    spans = _load_jsonl_spans()
    tool = next(s for s in spans if s.get("name", "").startswith("tool."))
    recs = spans_to_records([tool])
    assert len(recs) == 1
    assert recs[0]["type"] == "TOOL"
    assert recs[0]["id"] == tool["span_id"]
    assert recs[0]["trace_id"] == tool["trace_id"]


# ---- http.request SERVER → TRACE ----

def test_http_request_to_trace_record():
    spans = _load_jsonl_spans()
    root = next(s for s in spans if s.get("kind") == "SERVER" and not s.get("parent_span_id"))
    recs = spans_to_records([root])
    assert len(recs) == 1
    rec = recs[0]
    # TRACE record 无 type 字段 (对齐 trace_assembler), id == trace_id
    assert "type" not in rec
    assert rec["id"] == root["trace_id"]
    assert rec["session_id"] == root["conversation_id"]


# ---- 等价验证: spans → records → clean_traces ----

def test_clean_traces_from_jsonl_spans_matches_user_query():
    """standard 模式端到端: spans→records→clean_traces, task_input == root user_query。"""
    spans = _load_jsonl_spans()
    for trace_id, tspans in _by_trace(spans).items():
        root = next(s for s in tspans if s.get("kind") == "SERVER" and not s.get("parent_span_id"))
        request_body = json.loads(
            (root.get("attributes") or {}).get("openjiuwen.http.request_body")
        )
        user_query = request_body["user_query"]

        records = spans_to_records(tspans)
        cleaned = clean_traces(records, session_id=root["conversation_id"], agent_name="edp_agent")

        assert cleaned != {}, f"trace {trace_id}: clean_traces 返回空 (无 GENERATION?)"
        assert cleaned["task_input"] == user_query, f"trace {trace_id}: task_input 不等于 user_query"
        assert len(cleaned["messages"]) > 0, f"trace {trace_id}: messages 为空"
        # 最后一条是 assistant 输出
        assert cleaned["messages"][-1]["role"] == "assistant"


def test_clean_traces_last_generation_wins():
    """多条 GENERATION 时 clean_traces 取最后一条 (对齐 log-mode 行为)。"""
    spans = _load_jsonl_spans()
    trace_id, tspans = next(iter(_by_trace(spans).items()))
    records = spans_to_records(tspans)
    gens = [r for r in records if r.get("type") == "GENERATION"]
    assert len(gens) >= 2  # 该 trace 至少 2 个 llm.Model
    cleaned = clean_traces(records, session_id="conv", agent_name="edp")
    # 最后一个 GENERATION 的 output 应是 messages 末尾 (clean_traces 会剔除 usage_metadata)
    last_out = {k: v for k, v in gens[-1]["output"].items() if k != "usage_metadata"}
    assert cleaned["messages"][-1] == last_out


# ---- 未映射 span 跳过 ----

def test_unmapped_spans_skipped():
    """chain.EDPAgent / service.versatile_adapter 等不产 record (避免噪声)。"""
    spans = _load_jsonl_spans()
    chain = next(s for s in spans if s.get("name") == "chain.EDPAgent")
    recs = spans_to_records([chain])
    assert recs == []
