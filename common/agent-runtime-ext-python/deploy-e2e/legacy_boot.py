# coding: utf-8

"""以真 socket 起存量服务，供双侧对等比对使用。

## 为什么需要这个封装

存量的启动钩子存在一处顺序缺陷：它在赋值 `state.cache_store` **之前**就读取该项
（读在前、赋值在后），当前代码状态下起不来完整服务。本封装在进入其启动钩子前
先把该项补上，**不改存量任何行为**——补完之后走的仍是它自己的启动链路。

这不是「修存量」：补位只发生在本进程内、只为让它能起来受测。存量仓一个字节都不动。

## 起它需要什么

- **Redis**：存量启动即连接，无可用实例时起不来
- **仓内包路径**：`foundation`／`server`／`service` 与 `runtime/` 下各子包
  都未随主路径导出，须显式加入

二者由 `run-parity.sh` 准备。

## 确定性替身

设 `PARITY_FIXED_EVENTS=1` 时，在存量自己的启动钩子跑完之后，用**产出固定事件序列的
替身执行器**覆盖它装好的那个。真实智能体输出不确定，两侧无法逐字节比对；
替身把不确定性挡在外面，比的才是投影与传输这两段确定性逻辑。

**覆盖发生在启动钩子之后**，故存量的整条启动链路照常跑完——替换的只是最后那一个引用。
"""
from __future__ import annotations

import contextlib
import os

import app as legacy  # 存量应用
from config import get_settings
from oracle_support.cache_store_adapter import RedisClientCacheStore

from common.redis_client import RedisClient

_redis = RedisClient()
_original_lifespan = legacy.lifespan


@contextlib.asynccontextmanager
async def _lifespan_with_cache_store_prewired(fastapi_app):
    """在存量启动钩子之前补上它自己会读、但尚未赋值的那一项。"""
    await _redis.connect(get_settings().redis_url)
    fastapi_app.state.cache_store = RedisClientCacheStore(_redis)
    async with _original_lifespan(fastapi_app) as value:
        yield value


#: 与本版对等服务共用的事件序列。**两侧必须完全一致**，否则比的不是同一件事。
FIXED_EVENTS = [
    {"type": "thought", "data": {"content": "先看一下账户"}},
    {"type": "tool_start", "data": {"content": "调用查询", "plugin": "query_balance"}},
    # 与本版 `e2e_parity_server.FIXED_EVENTS` 的心跳帧逐字对应。
    # **不带 seq**：留给两侧各自的出流层兜底补号，那一段才是被比的东西。
    {"type": "heartbeat", "data": {"heartbeat_type": "normal", "status": "processing", "source": "a2a_service"}},
    {"type": "final_answer_chunk", "data": {"content": "余额为 100.00 元"}},
]


class _FixedExecutor:
    """产出固定事件序列的替身执行器。

    只实现分发路径对执行器的全部调用面：`execute` 与 `cancel_task`。

    **不关闭事件队列**——分发路径在执行返回后自己收尾（补终态、关闭）。
    替身抢着关会让它把这次执行判为未达终态的失败。
    """

    @staticmethod
    async def execute(ctx, event_queue):
        from channels.dict_to_a2a import dict_to_a2a

        for event in FIXED_EVENTS:
            await event_queue.enqueue_event(
                dict_to_a2a(event, getattr(ctx, "task_id", "t-1"), "conv-1")
            )

    @staticmethod
    async def cancel_task(conversation_id):
        return None


@contextlib.asynccontextmanager
async def _lifespan_with_fixed_executor(fastapi_app):
    """存量自己的启动链路跑完之后，把执行器换成确定性替身。"""
    async with _lifespan_with_cache_store_prewired(fastapi_app) as value:
        fastapi_app.state.executor = _FixedExecutor()
        yield value


legacy.app.router.lifespan_context = (
    _lifespan_with_fixed_executor
    if os.environ.get("PARITY_FIXED_EVENTS") == "1"
    else _lifespan_with_cache_store_prewired
)

#: 供 uvicorn 加载的应用对象。
app = legacy.app
