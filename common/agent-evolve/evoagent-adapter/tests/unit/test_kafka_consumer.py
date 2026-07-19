"""kafka_consumer.process_envelope 单测 —— fake repo, 无需 kafka。

钉住消费逻辑接线: OTLP 信封 → parse_otlp_envelope → repo.bulk_insert_spans。
parse_otlp_envelope 本身由 test_otlp_parser.py 覆盖; bulk_insert_spans 由
test_postgres_repository.py 覆盖; 此处验证消费者把两者正确串起。
"""

from __future__ import annotations

import json

import pytest

from agent_adapter.kafka_consumer.consumer import process_envelope
from agent_adapter.kafka_consumer.otlp_parser import parse_otlp_envelope


class FakeRepo:
    """记录 bulk_insert_spans 调用的假 repo。"""

    def __init__(self) -> None:
        self.bulks: list[list[dict]] = []

    async def bulk_insert_spans(self, spans: list[dict]) -> None:
        self.bulks.append(list(spans))


def _span(span_id: str, trace_id: str = "t1") -> dict:
    return {
        "traceId": trace_id,
        "spanId": span_id,
        "parentSpanId": "",
        "name": "http.request",
        "kind": 2,  # SERVER
        "startTimeUnixNano": "1000000000",
        "endTimeUnixNano": "2000000000",
        "attributes": [{"key": "session.id", "value": {"stringValue": "c1"}}],
        "status": {"code": 1},  # OK
    }


def _envelope(spans: list[dict]) -> dict:
    return {"resourceSpans": [{
        "resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "edp"}}]},
        "scopeSpans": [{"scope": {"name": "sc", "version": ""}, "spans": spans}],
    }]}


async def test_process_envelope_inserts_parsed_spans():
    env = _envelope([_span("s1"), _span("s2", trace_id="t2")])
    repo = FakeRepo()
    n = await process_envelope(repo, json.dumps(env).encode("utf-8"))
    assert n == 2
    assert len(repo.bulks) == 1
    # 入库的 spans 与 parse_otlp_envelope 直接解析结果一致
    assert repo.bulks[0] == parse_otlp_envelope(json.dumps(env))


async def test_process_envelope_accepts_str_and_bytes():
    env = _envelope([_span("s1")])
    repo = FakeRepo()
    assert await process_envelope(repo, json.dumps(env)) == 1
    assert await process_envelope(repo, json.dumps(env).encode()) == 1


async def test_process_envelope_empty_returns_zero():
    env = _envelope([])
    repo = FakeRepo()
    assert await process_envelope(repo, json.dumps(env)) == 0
    assert repo.bulks == []


async def test_process_envelope_bad_json_raises():
    repo = FakeRepo()
    with pytest.raises(Exception):
        await process_envelope(repo, b"not json")
    assert repo.bulks == []  # 失败前未入库
