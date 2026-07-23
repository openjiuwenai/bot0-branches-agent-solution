"""otlp_parser 单测: 用 synthetic spans 反构 OTLP 信封, 解析回扁平 dict, 断言字段一致。

验证 adapter 消费 kafka (OTLP 信封) 后得到的 span 形状与通用测试数据一致 ——
即设计文档点 7 "数据一致性" 在解析层的保证。
"""

from __future__ import annotations

import json
from datetime import datetime, timezone

from agent_adapter.kafka_consumer.otlp_parser import parse_otlp_envelope

from tests._testdata import otel_spans


def _load_spans() -> list[dict]:
    return otel_spans()


# ---- 反向映射: 扁平 span dict → OTLP 信封 (仅测试用) ----

_KIND_TO_INT = {"UNSPECIFIED": 0, "INTERNAL": 1, "SERVER": 2, "CLIENT": 3, "PRODUCER": 4, "CONSUMER": 5}
_STATUS_TO_INT = {"UNSET": 0, "OK": 1, "ERROR": 2}


def _iso_to_nano(iso: str) -> str:
    dt = datetime.fromisoformat(iso)
    return str(int(dt.timestamp()) * 1_000_000_000 + dt.microsecond * 1000)


def _to_anyvalue(v):
    if isinstance(v, bool):
        return {"boolValue": v}
    if isinstance(v, int):
        return {"intValue": str(v)}
    if isinstance(v, float):
        return {"doubleValue": v}
    if isinstance(v, str):
        return {"stringValue": v}
    if isinstance(v, list):
        return {"arrayValue": {"values": [_to_anyvalue(x) for x in v]}}
    if isinstance(v, dict):
        return {"kvlistValue": {"values": [{"key": k, "value": _to_anyvalue(val)} for k, val in v.items()]}}
    return {"stringValue": str(v)}


def _dict_to_attrs(d: dict) -> list[dict]:
    return [{"key": k, "value": _to_anyvalue(v)} for k, v in d.items()]


def _flat_to_otlp(span: dict) -> dict:
    """单 span → OTLP 信封 (自身一个 resourceSpans)。"""
    res_attrs = _dict_to_attrs(span.get("resource_attributes") or {})
    return {
        "resourceSpans": [{
            "resource": {"attributes": res_attrs},
            "scopeSpans": [{
                "scope": {"name": span.get("scope_name") or "", "version": span.get("scope_version") or ""},
                "spans": [{
                    "traceId": span["trace_id"],
                    "spanId": span["span_id"],
                    "parentSpanId": span.get("parent_span_id") or "",
                    "traceState": span.get("trace_state", ""),
                    "name": span.get("name", ""),
                    "kind": _KIND_TO_INT.get(span.get("kind"), 0),
                    "startTimeUnixNano": _iso_to_nano(span["start_time"]),
                    "endTimeUnixNano": _iso_to_nano(span["end_time"]),
                    "attributes": _dict_to_attrs(span.get("attributes") or {}),
                    "events": [],
                    "links": [],
                    "status": {
                        "code": _STATUS_TO_INT.get(span.get("status_code"), 0),
                        "message": span.get("status_message") or "",
                    },
                }],
            }],
        }]
    }


# ---- 字段比对 (jsonl 原始 vs 解析回) ----

def _assert_span_roundtrips(orig: dict, parsed: dict) -> None:
    # jsonl 的 16 字段
    for k in ("trace_id", "span_id", "parent_span_id", "name", "kind",
              "start_time", "end_time", "duration_ns", "status_code", "status_message",
              "scope_name", "scope_version"):
        assert parsed[k] == orig.get(k), f"{k}: {parsed[k]!r} != {orig.get(k)!r}"
    # attributes / resource_attributes (dict 等值)
    assert parsed["attributes"] == orig.get("attributes"), "attributes mismatch"
    assert parsed["resource_attributes"] == orig.get("resource_attributes"), "resource_attributes mismatch"
    # events/links (jsonl 均空)
    assert parsed["events"] == (orig.get("events") or [])
    assert parsed["links"] == (orig.get("links") or [])
    # 提升字段
    assert parsed["service_name"] == (orig.get("resource_attributes") or {}).get("service.name")
    assert parsed["conversation_id"] == (orig.get("attributes") or {}).get("session.id")
    # trace_state (jsonl 无此字段, OTLP 默认空)
    assert parsed["trace_state"] == orig.get("trace_state", "")


def test_roundtrip_all_synthetic_spans():
    spans = _load_spans()
    assert len(spans) > 0, "synthetic fixture 未生成 span"
    for orig in spans:
        envelope = _flat_to_otlp(orig)
        parsed_list = parse_otlp_envelope(json.dumps(envelope))
        assert len(parsed_list) == 1, f"解析出 {len(parsed_list)} 条, 期望 1"
        _assert_span_roundtrips(orig, parsed_list[0])


def test_bytes_input():
    spans = _load_spans()
    env = json.dumps(_flat_to_otlp(spans[0])).encode("utf-8")
    parsed = parse_otlp_envelope(env)
    assert parsed[0]["trace_id"] == spans[0]["trace_id"]


def test_multi_spans_in_one_envelope():
    spans = _load_spans()[:3]
    # 合并 3 个 span 到一个 resourceSpans (同 resource)
    res_attrs = _dict_to_attrs(spans[0].get("resource_attributes") or {})
    otlp_spans = []
    for s in spans:
        single = _flat_to_otlp(s)["resourceSpans"][0]["scopeSpans"][0]["spans"][0]
        otlp_spans.append(single)
    envelope = {"resourceSpans": [{"resource": {"attributes": res_attrs},
                                   "scopeSpans": [{"scope": {"name": "x", "version": ""}, "spans": otlp_spans}]}]}
    parsed = parse_otlp_envelope(json.dumps(envelope))
    assert len(parsed) == 3
    for orig, got in zip(spans, parsed):
        assert got["trace_id"] == orig["trace_id"]
        assert got["span_id"] == orig["span_id"]
