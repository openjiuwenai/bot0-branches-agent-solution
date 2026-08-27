# coding: utf-8

"""部署级 E2E 入口：**在途执行被取消后，Task 落取消终态**。

## 它补的是哪一块

`deploy-e2e/run-parity.sh` 只比对取消端点的**响应信封**，没有用例驱动
「在途执行 → 取消 → 查终态」。而取消的对外语义恰恰在终态上：
调用方主动取消时客户端在等一个明确答复，本轮须落取消终态；
关停排水时执行是被服务端掐断的，那一轮定性为「未完成」。

这条缺口在 `internal/ledger/ISSUE-LEDGER.md` 里**连续两轮被实证有价值**——
本仓的取消竞态缺陷正属这一层能当场抓到的形态，而进程内判据抓不到：
它们各自验「取消标志置位后消费循环会 break」，验不到从 HTTP 取消请求
到 Task 终态之间那一整段真实链路。

## 装配

慢智能体：首帧立刻产出（让客户端拿到 taskId），其后每 200 毫秒一帧共 12 秒。
脚本拿到首帧后调取消端点，再经**另一条连接**查 Task 终态。

**只替掉「慢」这件事本身**：取消传播、终态结算全是被测件在跑。
十二秒足够覆盖「取消没生效时它会自己走完」这种情形——那时终态是完成而不是取消，
判据据此能分出「取消生效了」与「碰巧执行结束了」。
"""
from __future__ import annotations

import asyncio
import os

from fastapi import FastAPI

from agent_runtime.bootstrap.a2a_app import create_a2a_app
from agent_runtime.domain.result import QueryChunk

_PORT = int(os.environ.get("PORT", "8090"))


class _SlowHandler:
    """慢智能体：首帧立刻，其后每 200 毫秒一帧，直到被取消。"""

    agent_id = "e2e-cancel-terminal"
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    @staticmethod
    async def query(request):
        from agent_runtime.domain.result import QueryResponse

        await asyncio.sleep(60)
        return QueryResponse(result="不该到达")

    @staticmethod
    async def stream_query(request):
        yield QueryChunk(type="chunk", data={"content": "开始处理"})
        for i in range(60):          # 12 秒，跨过取消时刻
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


app: FastAPI = create_a2a_app(
    _SlowHandler(),
    name="e2e-cancel-terminal",
    description="在途取消的终态验证",
    version="1.0.0",
    url=f"http://127.0.0.1:{_PORT}/a2a/",
    mount_path="/a2a",
)


@app.get("/health")
async def _health():
    return {"status": "ok", "mode": "cancel-terminal"}
