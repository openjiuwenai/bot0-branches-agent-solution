# coding: utf-8

"""部署级 E2E 变体：挂**标准 A2A northbound 面**（`create_a2a_app`）。

## 为什么单开一个变体

既有三个 E2E 变体（`e2e_server` / `e2e_questioner_server` / `e2e_remote_tool_server`
/ `e2e_client_tool_server`）挂的都是 `create_rest_app`——自定义 REST 面。
标准 A2A 面在容器里**从未被挂起来过**，这正是 `L2-overview` §8.2 那条
「四项断言主题在 `deploy-e2e` 下逐项零命中」的根因：不是漏写断言，是被测的面不在。

本变体把 `/a2a` 挂进容器，供 `run-a2a-northbound.sh` 打真 socket 断言
SSE 帧形态、JSON-RPC 错误码、Card 端点、三个 method。

## handler 为何是确定性桩

本变体验的是**协议面**（帧形态、错误码、method 分发、Card 端点），
不验业务执行——那由既有变体用真实 agent-core 覆盖。放真 LLM 进来只会让
协议断言受模型波动影响，把确定性门禁变成偶发红。
"""
from __future__ import annotations

import os

from fastapi import FastAPI

from agent_runtime.bootstrap.a2a_app import create_a2a_app
from agent_runtime.domain.result import QueryChunk

_AGENT_NAME = os.getenv("E2E_A2A_AGENT_NAME", "edp-a2a-e2e")


class _DeterministicHandler:
    """确定性 handler：一条内容帧后正常收流 → Task 走到 COMPLETED。"""
    # ── 端口协议要求的身份与健康成员（装配期按契约校验，缺一项即被拒收）──
    agent_id = "e2e-deterministic"
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    @staticmethod
    async def query(request):
        from agent_runtime.domain.result import QueryResponse

        return QueryResponse(output="ok")

    @staticmethod
    async def stream_query(request):
        yield QueryChunk(type="chunk", data={"content": "ok"})

    @staticmethod
    async def start():
        ...

    @staticmethod
    async def stop():
        ...

    @staticmethod
    async def clear_session(conversation_id):
        ...


class _SlowHandler:
    """慢智能体：迟迟不产出终态，用于在真实往返下触发执行等待窗口。

    **只替掉「慢」这件事本身**——窗口逻辑仍是被测件（`WaitWindowedRequestHandler`）在跑，
    替身没有绕过被验的那一段。
    """
    # ── 端口协议要求的身份与健康成员（装配期按契约校验，缺一项即被拒收）──
    agent_id = "e2e-slow"
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    @staticmethod
    async def query(request):
        import asyncio

        await asyncio.sleep(30)          # 远大于变体设定的窗口
        from agent_runtime.domain.result import QueryResponse

        return QueryResponse(output="不该到达")

    @staticmethod
    async def stream_query(request):
        import asyncio

        while True:                       # 持续产帧，用于触发消费等待窗口
            yield QueryChunk(type="chunk", data={"content": "帧"})
            await asyncio.sleep(0.05)

    @staticmethod
    async def start():
        ...

    @staticmethod
    async def stop():
        ...

    @staticmethod
    async def clear_session(conversation_id):
        ...


# E2E_INIT_FAIL_FAST 变体：装一个必然失败的初始化钩子，
# 用于在真实启动路径下观察 `runtime.lifecycle.init_fail_fast` 的两种行为
# （Feat-Func-000b §4.2.2）：真=服务起不来，假=降级起来并留痕。
# **只替掉「钩子会失败」这件事本身**，开关逻辑仍是被测件在跑。
async def _failing_init_hook() -> None:
    raise RuntimeError("E2E 故意失败的初始化钩子")


# E2E_A2A_WAIT_WINDOWS=1 切到「慢智能体 + 极小窗口」变体，
# 使两个等待窗口在真实 HTTP 往返下可观察（Feat-Func-001b §6.2）。
if os.getenv("E2E_A2A_WAIT_WINDOWS") == "1":
    app: FastAPI = create_a2a_app(
        _SlowHandler(),
        name=_AGENT_NAME,
        execution_wait_s=float(os.getenv("E2E_EXECUTION_WAIT_S", "1")),
        consume_wait_s=float(os.getenv("E2E_CONSUME_WAIT_S", "2")),
    )
elif os.getenv("E2E_INIT_FAIL_FAST") in ("0", "1"):
    app = create_a2a_app(
        _DeterministicHandler(),
        name=_AGENT_NAME,
        init_hooks=(_failing_init_hook,),
        init_fail_fast=os.getenv("E2E_INIT_FAIL_FAST") == "1",
    )
elif os.getenv("E2E_TASK_DB") == "1":
    # Task 快照档选取变体：**走产品装配路径**（`build_a2a_stores_with_init`），
    # 不在这里自己判配置。本变体要验的正是那条路径在真实进程里的行为：
    # 配全了就装数据库档并把初始化钩子挂上，配不全就在装配期让进程起不来。
    from agent_runtime.bootstrap.cache_wiring import (  # noqa: PLC0415
        build_a2a_stores_with_init,
    )
    from agent_runtime.bootstrap.config.loader import (  # noqa: PLC0415
        ConfigSource,
        SourceKind,
    )

    _cfg = os.getenv("OPENJIUWEN__SERVICE__CONFIG_FILE", "")
    _sources = (ConfigSource(SourceKind.FILE, _cfg),) if _cfg else ()
    _task_store, _cache_client, _task_store_init = build_a2a_stores_with_init(_sources)
    app = create_a2a_app(
        _DeterministicHandler(),
        name=_AGENT_NAME,
        task_store=_task_store,
        init_hooks=tuple(hook for hook in (_task_store_init,) if hook is not None),
    )
else:
    app = create_a2a_app(_DeterministicHandler(), name=_AGENT_NAME)


@app.get("/health")
async def _health():
    return {"status": "ok", "mode": "a2a-northbound", "agent": _AGENT_NAME}
