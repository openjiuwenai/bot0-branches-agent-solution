"""kafka_consumer.process_envelope 单测 —— fake repo, 无需 kafka。

钉住消费逻辑接线: OTLP 信封 → parse_otlp_envelope → repo.bulk_insert_spans。
parse_otlp_envelope 本身由 test_otlp_parser.py 覆盖; bulk_insert_spans 由
test_postgres_repository.py 覆盖; 此处验证消费者把两者正确串起。
"""

from __future__ import annotations

import asyncio
import json

import pytest

from agent_adapter.kafka_consumer.consumer import TraceConsumer, process_envelope
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


async def test_consume_loop_without_start_raises_runtime_error():
    """start() 未调用前 _consumer 为 None，启动前置校验须抛 RuntimeError 而非 AssertionError。"""
    consumer = TraceConsumer(FakeRepo(), "localhost:9092")
    with pytest.raises(RuntimeError, match="未 start"):
        await consumer._consume_loop()


async def test_handle_without_start_raises_runtime_error():
    """_handle 在 start() 前调用须抛 RuntimeError（msg 在校验通过后才被访问）。"""
    consumer = TraceConsumer(FakeRepo(), "localhost:9092")
    with pytest.raises(RuntimeError, match="未 start"):
        await consumer._handle(None)


class _CancelConsumer:
    """aiokafka-like: async 迭代首条即抛 CancelledError，验证消费循环不吞取消。"""

    def __aiter__(self):
        return self

    async def __anext__(self):
        raise asyncio.CancelledError()

    async def stop(self) -> None:  # pragma: no cover
        pass


async def test_consume_loop_propagates_cancellation():
    """CancelledError 属 BaseException，须穿透 except Exception 传播而非被吞入重试。"""
    consumer = TraceConsumer(FakeRepo(), "localhost:9092")
    consumer._consumer = _CancelConsumer()  # type: ignore[assignment]
    with pytest.raises(asyncio.CancelledError):
        await consumer._consume_loop()
