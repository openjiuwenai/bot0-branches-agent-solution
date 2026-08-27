# coding: utf-8

"""对等比对用的本版服务：装确定性替身，只覆盖投影与传输两段。

与 `e2e_server.py` 的区别：那个装真实框架驱动真实工作流，用于验证本版自身跑得通；
本文件装**产出固定事件序列的替身**，用于与存量逐字节对等比对——
真实智能体输出不确定，两侧无法逐字节比。
"""
from __future__ import annotations

import os

from fastapi import FastAPI

from agent_runtime.bootstrap.rest_app import create_rest_app
from agent_runtime.domain.result import QueryChunk

#: 与存量侧共用的事件序列。**逐字对应 `legacy_boot.FIXED_EVENTS`**——
#: 两侧不一致时比的就不是同一件事，而这种不一致不会有任何东西报错。
#: 形态取自存量真实帧序（单个子智能体从开始到完成）。
FIXED_EVENTS: list[tuple] = [
    ("thought", "先看一下账户", "", {}),
    ("tool_start", "调用查询", "query_balance", {}),
    # 心跳帧：**由处理器产出、runtime 只补序号**，这正是本版的分工
    # （宿主义务 H-SERVE-6：runtime 不产 heartbeat 帧）。
    # 加它进对等面是因为「补号」这一段此前零覆盖——存量在出流层兜底补号
    # （`.legacy-oracle/applications/a2a_service/api/dispatch.py` 的 `_serialize_event`），
    # 我方在 `agent_runtime/adapters/inbound/rest/router.py` 的 `_stamp_heartbeat_seq`，
    # 两段谁改坏了都不会有任何东西报错。
    # **不带 seq**：带了就绕过两侧的兜底分支，比的就不是补号了。
    ("heartbeat", "", "", {"heartbeat_type": "normal", "status": "processing", "source": "a2a_service"}),
    ("final_answer_chunk", "余额为 100.00 元", "", {}),
]


class _FixedHandler:
    """产出固定结果块序列的替身处理器。"""
    # ── 端口协议要求的身份与健康成员（装配期按契约校验，缺一项即被拒收）──
    agent_id = "e2e-parity"
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    async def query(self, request):  # noqa: ANN001,ANN201
        """阻塞路径：把流式排干。与编排层的实际形态一致，不另写一套可能分叉的逻辑。"""
        from agent_runtime.domain.result import QueryResponse

        chunks = [chunk async for chunk in self.stream_query(request)]
        return QueryResponse(
            result=chunks[-1].data if chunks else None,
            conversation_id=getattr(request, "conversation_id", ""),
        )

    async def clear_session(self, conversation_id: str) -> None:
        """本替身不持会话态，无可清理。"""

    @staticmethod
    async def stream_query(request):  # noqa: ANN001, ARG002
        for event_type, content, plugin, data in FIXED_EVENTS:
            yield QueryChunk.of_event(event_type, content=content, data=data, plugin=plugin)

    @staticmethod
    async def start():
        return None

    @staticmethod
    async def stop():
        return None


def _build_session_store():
    """接真实 Redis，与存量**同一实例同一库**（Feat-Func-004b §2.3.1.1 的双写验证）。

    未配 `PARITY_REDIS_PORT` 时返回 None——写侧随之静默跳过，
    其余比对项照跑。**不因缺 Redis 而让整个脚本跳过**：
    执行前三关与执行中面的比对不依赖它。
    """
    port = os.environ.get("PARITY_REDIS_PORT")
    if not port:
        return None
    import redis.asyncio as aioredis

    from agent_runtime.adapters.outbound.cache_redis.standalone import (
        StandaloneRuntimeRedisClient,
    )
    from agent_runtime.adapters.outbound.session.shared_keys import SharedSessionStore

    return SharedSessionStore(
        StandaloneRuntimeRedisClient(
            aioredis.Redis(
                host=os.environ.get("PARITY_REDIS_HOST", "127.0.0.1"),
                port=int(port),
                db=int(os.environ.get("PARITY_REDIS_DB", "0")),
                decode_responses=False,
            )
        )
    )


app: FastAPI = create_rest_app(_FixedHandler(), session_store=_build_session_store())


@app.get("/health")
async def health() -> dict:
    """健康端点——启动脚本据此判就绪。

    形态与存量一致（`applications/a2a_service/app.py` 的 `health_check`），
    但它属应用装配层而非本特性；此处仅为受测服务提供就绪信号。
    """
    return {"status": "healthy", "service": "A2A Service"}
