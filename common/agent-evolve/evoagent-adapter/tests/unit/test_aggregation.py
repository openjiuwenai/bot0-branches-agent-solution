"""repository.aggregation 纯函数单测 — 无 PG, 无 asyncpg。

用代码内生成的通用 synthetic OTel spans 做基准, 钉住:
- 根 span 判定 (kind=SERVER 且 parent 为空)
- status 取最差 (ERROR > OK > UNSET)
- traces 汇总聚合 (span_count / 起止时间 / root 字段 / summary, 与插入顺序无关)
- span 树重建 (parent/children 嵌套)

这些纯函数是 postgres.py 的单一真相源; I/O 由集成测试覆盖。
"""

from __future__ import annotations

from agent_adapter.repository.aggregation import (
    build_trace_tree,
    compute_trace_summary,
    is_root_span,
    worst_status,
)

from tests._testdata import otel_spans


def _load_spans() -> list[dict]:
    """加载 synthetic spans 并提升 service_name/conversation_id。"""
    spans = otel_spans()
    # 提升字段 (mimic parse_span): service_name ← resource_attributes.service.name
    # conversation_id ← attributes.session.id
    for s in spans:
        s.setdefault("service_name", (s.get("resource_attributes") or {}).get("service.name"))
        s.setdefault("conversation_id", (s.get("attributes") or {}).get("session.id"))
    return spans


# ---- 根 span 判定 ----

def test_is_root_span_matches_synthetic_spans():
    spans = _load_spans()
    roots = [s for s in spans if is_root_span(s)]
    # synthetic fixture: 3 trace, 每个 1 个 http.request SERVER 根 span
    assert len(roots) == 3
    assert {r["name"] for r in roots} == {"http.request"}
    assert all(r["kind"] == "SERVER" for r in roots)
    assert all(not r.get("parent_span_id") for r in roots)


def test_is_root_span_non_server_not_root():
    # INTERNAL span 即便无 parent 也不是会话根
    internal_no_parent = {"kind": "INTERNAL", "parent_span_id": "", "span_id": "x"}
    assert is_root_span(internal_no_parent) is False


def test_is_root_span_server_with_parent_not_root():
    # SERVER 但有 parent 不是根
    s = {"kind": "SERVER", "parent_span_id": "abc", "span_id": "y"}
    assert is_root_span(s) is False


# ---- worst_status ----

def test_worst_status_precedence():
    assert worst_status(["OK", "UNSET"]) == "OK"
    assert worst_status(["OK", "ERROR"]) == "ERROR"
    assert worst_status(["UNSET", "OK", "ERROR"]) == "ERROR"
    assert worst_status(["OK", "OK"]) == "OK"


def test_worst_status_empty_is_unset():
    assert worst_status([]) == "UNSET"


def test_worst_status_unknown_treated_as_unset():
    assert worst_status(["WEIRD", "OK"]) == "OK"
    assert worst_status(["WEIRD"]) == "UNSET"


# ---- compute_trace_summary ----

def _by_trace(spans: list[dict]) -> dict[str, list[dict]]:
    groups: dict[str, list[dict]] = {}
    for s in spans:
        groups.setdefault(s["trace_id"], []).append(s)
    return groups


def test_compute_trace_summary_synthetic_spans():
    spans = _load_spans()
    for trace_id, tspans in _by_trace(spans).items():
        root = next(s for s in tspans if is_root_span(s))
        rattrs = root.get("attributes") or {}
        summ = compute_trace_summary(trace_id, tspans)

        assert summ["trace_id"] == trace_id
        assert summ["span_count"] == len(tspans)
        assert summ["status"] == "OK"  # fixture 全 OK
        assert summ["root_span_id"] == root["span_id"]
        assert summ["conversation_id"] == rattrs["session.id"]
        assert summ["user_id"] == rattrs["user.id"]
        assert summ["openjiuwen_trace_id"] == rattrs["openjiuwen.trace.id"]
        assert summ["request_summary"] == rattrs["openjiuwen.http.request_body"]
        assert summ["response_summary"] == rattrs["openjiuwen.http.response_summary"]
        assert summ["service_name"] == root.get("service_name")
        assert summ["start_time"] == min(s["start_time"] for s in tspans)
        assert summ["end_time"] == max(s["end_time"] for s in tspans)


def test_compute_trace_summary_order_independent():
    """乱序插入 (kafka 可能根 span 后到) 不影响汇总。"""
    spans = _load_spans()
    trace_id, tspans = next(iter(_by_trace(spans).items()))
    baseline = compute_trace_summary(trace_id, tspans)
    # 逆序 (根 span 移到末尾) + 旋转一位, 覆盖根 span 后到的场景
    for reordered in (list(reversed(tspans)), tspans[1:] + tspans[:1]):
        assert compute_trace_summary(trace_id, reordered) == baseline


def test_compute_trace_summary_error_propagates_from_non_root():
    """任一 span (含非根) ERROR → trace status ERROR。"""
    spans = _load_spans()
    trace_id, tspans = next(iter(_by_trace(spans).items()))
    # 把一个非根 span 标 ERROR
    non_root = next(s for s in tspans if not is_root_span(s))
    non_root = {**non_root, "status_code": "ERROR"}
    altered = [non_root if s["span_id"] == non_root["span_id"] else s for s in tspans]
    assert compute_trace_summary(trace_id, altered)["status"] == "ERROR"


def test_compute_trace_summary_no_root():
    """无 SERVER 根 span: root_span_id / summary / user_id 为 None, 其余仍聚合。"""
    spans = _load_spans()
    trace_id, tspans = next(iter(_by_trace(spans).items()))
    no_root = [{**s, "kind": "INTERNAL"} for s in tspans]  # 抹掉 SERVER
    summ = compute_trace_summary(trace_id, no_root)
    assert summ["root_span_id"] is None
    assert summ["request_summary"] is None
    assert summ["response_summary"] is None
    assert summ["user_id"] is None
    assert summ["span_count"] == len(tspans)
    assert summ["status"] == "OK"


def test_compute_trace_summary_empty_raises():
    import pytest

    with pytest.raises(ValueError):
        compute_trace_summary("t", [])


# ---- build_trace_tree ----

def test_build_trace_tree_nests_by_parent():
    spans = _load_spans()
    trace_id, tspans = next(iter(_by_trace(spans).items()))
    tree = build_trace_tree(tspans)
    assert tree is not None
    root = next(s for s in tspans if is_root_span(s))
    assert tree["span_id"] == root["span_id"]
    # 根的 children = parent 指向根的 spans
    direct_children = [s for s in tspans if s.get("parent_span_id") == root["span_id"]]
    assert {c["span_id"] for c in tree["children"]} == {s["span_id"] for s in direct_children}
    # 全部 span 恰好出现一次 (含嵌套)
    seen: list[str] = []

    def walk(node: dict) -> None:
        seen.append(node["span_id"])
        for c in node.get("children", []):
            walk(c)

    walk(tree)
    assert sorted(seen) == sorted(s["span_id"] for s in tspans)


def test_build_trace_tree_empty_is_none():
    assert build_trace_tree([]) is None
