"""TraceConsumer kafka 集成测试 —— 真 produce + 真 consume → PG。

依赖宿主能解析 kafka broker (collector compose 的 ADVERTISED_LISTENERS=PLAINTEXT://kafka:9092,
宿主需 hosts: 127.0.0.1 kafka; 或在 docker 网络内运行)。session fixture 先探活
produce+consume, 失败/超时则整模块 skip (不阻塞默认测试运行)。

PG fixtures (repo) 见 tests/integration/conftest.py。
"""

from __future__ import annotations

import asyncio
import json
import os

import pytest
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from agent_adapter.kafka_consumer.consumer import TraceConsumer

_BROKERS = os.environ.get("ADAPTER_TEST_KAFKA_BROKERS", "127.0.0.1:9092")


def _span(span_id: str, trace_id: str = "t1") -> dict:
    return {
        "traceId": trace_id, "spanId": span_id, "parentSpanId": "",
        "name": "http.request", "kind": 2,
        "startTimeUnixNano": "1000000000", "endTimeUnixNano": "2000000000",
        "attributes": [{"key": "session.id", "value": {"stringValue": "c1"}}],
        "status": {"code": 1},
    }


def _envelope(spans: list[dict]) -> dict:
    return {"resourceSpans": [{
        "resource": {"attributes": [{"key": "service.name", "value": {"stringValue": "edp"}}]},
        "scopeSpans": [{"scope": {"name": "sc", "version": ""}, "spans": spans}],
    }]}


async def _roundtrip_ok(brokers: str) -> bool:
    """短超时 produce+consume 探活 (检测 advertised-listener 不可达等)。"""
    topic = f"adapter_probe_{os.getpid()}"
    p = AIOKafkaProducer(bootstrap_servers=brokers)
    try:
        await p.start()
        await p.send_and_wait(topic, b"ping")
    except Exception:
        return False
    finally:
        await p.stop()
    try:
        c = AIOKafkaConsumer(
            topic, bootstrap_servers=brokers, group_id=f"adapter_probe_{os.getpid()}",
            auto_offset_reset="earliest", enable_auto_commit=False,
        )
        await c.start()
        try:
            async for _ in c:
                await c.commit()
                return True
        finally:
            await c.stop()
    except Exception:
        return False
    return False


@pytest.fixture(scope="session")
def kafka_broker():
    """探活 kafka; 不可达 (含 advertised-listener 宿主解析失败) 则跳过本模块。"""
    try:
        ok = asyncio.run(asyncio.wait_for(_roundtrip_ok(_BROKERS), timeout=10))
    except Exception:
        ok = False
    if not ok:
        pytest.skip(
            "kafka 不可用或 advertised-listener 宿主不可解析 "
            "(需 hosts: 127.0.0.1 kafka, 或在 docker 网络内运行)"
        )
    return _BROKERS


async def test_consumer_persists_spans_to_pg(repo, kafka_broker):
    """produce OTLP 信封 → TraceConsumer 消费 → PG 落 2 span。"""
    trace_id = "kafka-t1"
    env = _envelope([_span("s1", trace_id), _span("s2", trace_id)])
    topic = f"adapter_test_{os.getpid()}"
    group = f"adapter_test_grp_{os.getpid()}"

    p = AIOKafkaProducer(bootstrap_servers=kafka_broker)
    await p.start()
    try:
        await p.send_and_wait(topic, json.dumps(env).encode("utf-8"))
    finally:
        await p.stop()

    consumer = TraceConsumer(repo, brokers=kafka_broker, topic=topic, group_id=group)
    await consumer.start()
    try:
        got: list = []
        for _ in range(40):  # 轮询 ~10s 等消费完成
            got = await repo.get_spans_by_trace(trace_id)
            if len(got) >= 2:
                break
            await asyncio.sleep(0.25)
        assert len(got) == 2, f"期望 2 span, 实际 {len(got)} (消费未完成?)"
        assert {s["span_id"] for s in got} == {"s1", "s2"}
        # traces 汇总也由 bulk_insert_spans 维护
        async with repo.pool.acquire() as conn:
            cnt = await conn.fetchval("SELECT span_count FROM traces WHERE trace_id=$1", trace_id)
        assert cnt == 2
    finally:
        await consumer.stop()


async def test_consumer_poison_message_skipped(repo, kafka_broker):
    """poison 消息 (非法 JSON) 被 commit 跳过, 不阻塞后续正常消息。"""
    topic = f"adapter_test_poison_{os.getpid()}"
    group = f"adapter_test_poison_grp_{os.getpid()}"
    trace_id = "kafka-t2"
    good_env = _envelope([_span("g1", trace_id)])

    p = AIOKafkaProducer(bootstrap_servers=kafka_broker)
    await p.start()
    try:
        await p.send_and_wait(topic, b"not json at all")  # poison
        await p.send_and_wait(topic, json.dumps(good_env).encode("utf-8"))
    finally:
        await p.stop()

    consumer = TraceConsumer(repo, brokers=kafka_broker, topic=topic, group_id=group)
    await consumer.start()
    try:
        got: list = []
        for _ in range(40):
            got = await repo.get_spans_by_trace(trace_id)
            if len(got) >= 1:
                break
            await asyncio.sleep(0.25)
        assert len(got) == 1, f"poison 应被跳过, 正常消息应入库; 实际 {len(got)}"
    finally:
        await consumer.stop()
