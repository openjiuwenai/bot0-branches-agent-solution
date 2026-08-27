# coding: utf-8

"""部署级 E2E 入口：**活动 Task 的重订阅**。

## 它补的是哪一块

权威 `FEAT-001` 那条 MUST 要求 runtime 支持 `SubscribeToTask(params.id=taskId)`，
让**另一个连接**接着收订阅时点之后的事件。

这条此前只有一个进程内判据，且**挂着 `xfail(strict=True)`**，
reason 写的是「流式执行途中 Task 已被标终态，重订阅因此拿不到活动 Task」。
2026-08-25 实测推翻了那个结论——**结论错在工具，不在产品**：

那个判据用 `httpx.ASGITransport` 驱动。**它不是流式的**：把整个 ASGI 应用跑完、
收齐全部响应体，才把响应交给客户端。实测时序（处理器阻塞 15 秒）：

    [  0.00s] handler 进入等待
    [ 15.00s] handler 等待超时、跑完
    [ 15.01s] 客户端才拿到响应头        ← 此刻 Task 早已落终态

也就是说「原流仍开着」这个场景**在进程内传输下根本构造不出来**。
判据据此得出「执行还没跑完 Task 已被标完成」，而实际是执行确实已经结束了。

换成真 socket 后（本入口），同一段代码的读数是：

    [  0.04s] handler 首帧
    [  0.04s] handler 阻塞等待           ← 执行中
    [  0.05s] 客户端发 SubscribeToTask（原流仍开着）
    [  0.05s] 200 / text/event-stream，首帧 state = TASK_STATE_WORKING

**拿到的正是活动 Task。** 这条能力一直是好的，只是从没被验证过。

## 慢在哪

处理器首帧立刻产出（让客户端拿到 taskId），随后阻塞 20 秒——
足够第二条连接在**执行中**发起重订阅。**只替掉「慢」这件事本身**：
分派、订阅、出流全是被测件在跑，替身没有绕过被验的那一段。
"""
from __future__ import annotations

import asyncio
import os

from fastapi import FastAPI

from agent_runtime.bootstrap.a2a_app import create_a2a_app
from agent_runtime.domain.result import QueryChunk


class _BlockingHandler:
    """首帧立刻产出，其后阻塞——留出「执行中」这个窗口。"""

    agent_id = "e2e-resubscribe"
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
        # 20 秒足够脚本在执行中发起重订阅并读到首帧；到点自行收尾，
        # 不让容器挂死——挂死的读数与失败无法分辨。
        await asyncio.sleep(20)
        yield QueryChunk(type="chunk", data={"content": "收尾"})

    @staticmethod
    async def start() -> None:
        ...

    @staticmethod
    async def stop() -> None:
        ...

    @staticmethod
    async def clear_session(conversation_id: str) -> None:
        ...


app: FastAPI = create_a2a_app(_BlockingHandler(), name="e2e-resubscribe")


@app.get("/health")
async def _health() -> dict[str, str]:
    return {"status": "ok", "mode": "resubscribe", "port": os.environ.get("PORT", "")}
