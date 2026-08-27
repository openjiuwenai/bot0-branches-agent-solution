# coding: utf-8

"""部署级 E2E 入口：**真 socket 断连**下的结算行为。

## 它补的是哪一块

`agent_runtime/tests/test_client_disconnect_settlement.py` 已覆盖两种注入形态
（`aclose()` 的 `GeneratorExit` 与 `Task.cancel()` 的 `CancelledError`），
但它们都在**进程内**注入，且测的是自定义 REST 入口。
**断连是传输层的物理事实**——进程内造不出「对端 TCP 连接没了」，
也就测不到从 socket 断到执行侧之间那一整段真实链路
（`internal/ledger/ISSUE-LEDGER.md` 的 R12-10 逐字登记了这条缺口）。

本入口起一个慢智能体，让客户端在执行中途真的把连接掐掉，
再经另一条连接查 Task——**验的是权威 `CL-84e49b50d3a6` 那条 MUST**：
「当 SSE 连接因客户端超时、网络中断或客户端主动关闭而断开时，
Task 必须继续在当前生命周期状态执行，不得因 SSE 连接断开而转为 failed 或 canceled」。

**两个面的要求相反，不要互相套用**：自定义 REST 面（FEAT-022）那侧
断连不得结算成完成态；标准 A2A 面这侧断连**不影响** Task 生命周期。
`CL-280dadc03984` 的场景表与 `:178` 的边界表各重申一次。

## 慢在哪、慢多久

处理器首帧立刻产出（让客户端拿到 taskId），之后每 200 毫秒一帧、共 6 秒。
客户端在拿到首帧后断开——那时执行正在进行中，正是要验的时刻；
六秒足够让脚本再看一次「它有没有自己走完」。

**只替掉「慢」这件事本身**：结算逻辑仍是被测件在跑，替身没有绕过被验的那一段。
"""
from __future__ import annotations

import asyncio
import os

from fastapi import FastAPI

from agent_runtime.bootstrap.a2a_app import create_a2a_app
from agent_runtime.domain.result import QueryChunk


class _SlowHandler:
    """慢智能体：首帧立刻、其后每 200 毫秒一帧，直到被取消。"""

    agent_id = "e2e-disconnect"
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    @staticmethod
    async def query(request):
        from agent_runtime.domain.result import QueryResponse

        await asyncio.sleep(30)
        return QueryResponse(result="不该到达")

    @staticmethod
    async def stream_query(request):
        yield QueryChunk(type="chunk", data={"content": "开始处理"})
        for i in range(30):              # 6 秒，跨过客户端断连时刻并自然走完
            await asyncio.sleep(0.2)
            yield QueryChunk(type="chunk", data={"content": f"处理中 {i}"})

    @staticmethod
    async def start() -> None:
        ...

    @staticmethod
    async def stop() -> None:
        ...

    @staticmethod
    async def clear_session(conversation_id: str) -> None:
        ...


app: FastAPI = create_a2a_app(_SlowHandler(), name="e2e-disconnect")


@app.get("/health")
async def _health() -> dict[str, str]:
    return {"status": "ok", "mode": "disconnect", "port": os.environ.get("PORT", "")}
