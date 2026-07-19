"""kafka 消费者 —— 订阅 otlp_traces, 摊平 OTLP 信封, 入库 PG。

同进程 asyncio task, 生命周期随 FastAPI app (startup 起, shutdown 停)。
一条 kafka 消息 = 一个 OTLP JSON 信封 (resourceSpans[].scopeSpans[].spans[]),
经 parse_otlp_envelope 摊平为 per-span dict 列表, 一条消息一个事务 bulk 入库。

参考: 旧 trace-pg-sink compose/collector/sink/consumer.py (kafka-python + pg8000 同步版)。
本模块为 async (aiokafka + asyncpg), 与 FastAPI 同事件循环。

提交策略 (对齐旧 sink):
- 解析失败 (poison 消息) → 记日志 + commit 跳过 (避免无限重投)。
- 入库失败 → 不 commit, kafka 重投 (依赖 repo 事务回滚)。
- 成功 → commit。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from aiokafka import AIOKafkaConsumer

from agent_adapter.kafka_consumer.otlp_parser import parse_otlp_envelope
from agent_adapter.repository.base import TraceRepository

logger = logging.getLogger(__name__)


async def process_envelope(repo: TraceRepository, message: bytes | str) -> int:
    """一条 OTLP kafka 消息 → 解析 → bulk 入库; 返回入库 span 数。

    解析失败抛异常 (调用方决定跳过/重试); 空信封返回 0 不入库。
    纯逻辑 (只依赖 repo 接口), 便于用 fake repo 单测, 无需 kafka。
    """
    spans = parse_otlp_envelope(message)
    if not spans:
        return 0
    await repo.bulk_insert_spans(spans)
    return len(spans)


class TraceConsumer:
    """otlp_traces 消费者: aiokafka asyncio task, 生命周期随 app。"""

    def __init__(
        self,
        repo: TraceRepository,
        brokers: str,
        *,
        topic: str = "otlp_traces",
        group_id: str = "agent-adapter",
    ) -> None:
        self._repo = repo
        self._brokers = brokers
        self._topic = topic
        self._group_id = group_id
        self._consumer: AIOKafkaConsumer | None = None
        self._task: asyncio.Task[None] | None = None

    async def start(self) -> None:
        """启动 consumer + 后台消费循环。"""
        self._consumer = AIOKafkaConsumer(
            self._topic,
            bootstrap_servers=self._brokers.split(","),
            group_id=self._group_id,
            auto_offset_reset="earliest",
            enable_auto_commit=False,
            value_deserializer=lambda b: b,  # 原始 bytes, process_envelope 接受 bytes|str
        )
        await self._consumer.start()
        logger.info(
            "kafka_consumer_started topic=%s brokers=%s group=%s",
            self._topic, self._brokers, self._group_id,
        )
        self._task = asyncio.create_task(self._consume_loop())

    async def stop(self) -> None:
        """停消费循环 + 关 consumer (随 app shutdown)。"""
        if self._task is not None:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass
            self._task = None
        if self._consumer is not None:
            await self._consumer.stop()
            self._consumer = None
        logger.info("kafka_consumer_stopped topic=%s", self._topic)

    async def _consume_loop(self) -> None:
        assert self._consumer is not None
        async for msg in self._consumer:
            await self._handle(msg)

    async def _handle(self, msg: Any) -> None:
        """处理一条消息: 解析跳过/入库重试/成功提交。"""
        assert self._consumer is not None
        try:
            spans = parse_otlp_envelope(msg.value)
        except Exception:
            logger.exception("kafka_parse_error offset=%s topic=%s", msg.offset, msg.topic)
            await self._consumer.commit()  # poison 消息: 跳过
            return
        if not spans:
            await self._consumer.commit()
            return
        try:
            await self._repo.bulk_insert_spans(spans)
        except Exception:
            logger.exception("kafka_insert_error offset=%s topic=%s (不提交, 待重投)", msg.offset, msg.topic)
            return  # 不 commit, kafka 重投
        await self._consumer.commit()
