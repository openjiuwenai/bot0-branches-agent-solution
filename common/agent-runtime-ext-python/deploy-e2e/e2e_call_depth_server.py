# coding: utf-8

"""调用深度收敛的部署级 E2E（Feat-Func-004b §6.3.1.1）。

## 为什么进程内判据不够

进程内判据分段验证：入站读得对不对、南向拼得对不对、判定判得对不对。
真实往返多覆盖两样，而这两样恰好是本项此前失效的地方：

| 多出的 | 为什么进程内看不到 |
|---|---|
| 路径真的经真实 A2A 报文过了河 | 进程内直接构造映射；protobuf 的 `Struct` 转换若丢了这个键，进程内照样绿 |
| 深度真的在**跨进程的下一跳**被读出来 | 进程内的「下一跳」是同一个函数调用，不经序列化 |

第二样是要害：`sub_task_path` 要从本节点的数据片段写出去、经 protobuf、
再被下一跳的入站适配器读回来。**这条链上任一环丢键，表现都是「深度恒定不变」
而不是任何报错**——收敛静默失效，链路照跑。

## 装置

一个服务同时扮演两个角色：

- `/a2a/` 是**被调方**：它的处理器把收到的入站路径原样回报（作为终答文本），
  于是主调方能看到「下一跳究竟读到了什么路径」。
- `/drive-depth` 是**主调方**：带上指定的父路径发起一次真实远端调用。

主调方的父路径由查询参数给定，模拟「本节点是链路上第 N 跳」。
"""
from __future__ import annotations

import json
import os
from typing import AsyncIterator

import httpx

from agent_runtime.adapters.outbound.remote.batch_runner import RemoteBatchExecutor
from agent_runtime.adapters.outbound.remote.config import RemoteAgentConfig, RemoteEndpoint
from agent_runtime.adapters.outbound.remote.member_caller import RemoteMemberCaller
from agent_runtime.bootstrap.a2a_app import create_a2a_app
from agent_runtime.bootstrap.remote_wiring import build_remote_coordinator
from agent_runtime.domain.remote.delegation import RemoteDelegation
from agent_runtime.domain.result import QueryChunk

_PORT = int(os.environ.get("PORT", "8090"))
_SELF_ROOT = os.environ.get("SELF_ROOT_URL", f"http://127.0.0.1:{_PORT}")
_SELF_A2A = os.environ.get("SELF_A2A_URL", f"{_SELF_ROOT}/a2a/")
_AGENT_ID = "depth-echo"


class _PathEchoHandler:
    """被调方：把**入站读到的层级路径**作为终答回报。

    这是本项的观察窗口——路径若在过河途中丢了，这里回报的就是空。
    """

    agent_id = _AGENT_ID
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True

    @staticmethod
    async def stream_query(request) -> AsyncIterator[QueryChunk]:
        yield QueryChunk.of_final_answer(
            json.dumps(
                {
                    "inbound_path": list(request.sub_task_path),
                    "inbound_depth": len(request.sub_task_path),
                },
                ensure_ascii=False,
            )
        )

    async def query(self, request):
        chunks = [c async for c in self.stream_query(request)]
        return chunks[-1] if chunks else None

    @staticmethod
    async def start() -> None:
        ...

    @staticmethod
    async def stop() -> None:
        ...

    @staticmethod
    async def clear_session(conversation_id: str) -> None:
        ...


app = create_a2a_app(
    _PathEchoHandler(),
    name=_AGENT_ID,
    description="深度收敛 E2E：回报入站层级路径",
    version="1.0.0",
    url=_SELF_A2A,
)


@app.get("/health")
async def _health():
    return {"status": "ok", "mode": "call-depth", "self_a2a": _SELF_A2A}


@app.get("/drive-depth")
async def _drive(parent: str = "", limit: int = 3):
    """带指定父路径发起真实远端调用。

    参数 parent：父路径，逗号分隔（空串表示首跳）。
    参数 limit：深度上限。

    返回里 `echo` 是**下一跳读到的路径**——路径过河没过河，看它。
    """
    parent_path = tuple(p for p in parent.split(",") if p)
    config = RemoteAgentConfig(
        endpoints=(RemoteEndpoint(agent_id=_AGENT_ID, url=_SELF_A2A, timeout_s=30.0),)
    )
    async with httpx.AsyncClient(timeout=30) as hc:
        coordinator, _ = await build_remote_coordinator(config, httpx_client=hc)

        class _Store:
            """会话缓存替身——只挡住「上游原始请求从哪来」这一处不确定性。

            路径不取自它：路径来自委派本身（§6.3.1.1 的合成规则），
            由被测件计算，不是替身喂的。
            """

            @staticmethod
            async def get_request(conversation_id):
                return {"headers": {}, "params": {}, "trace_id": "", "body": {}}

        caller = RemoteMemberCaller(
            coordinator=coordinator,
            # 就地定义的最小会话存储替身，只实现本探针会走到的方法面。
            session_store=_Store(),  # type: ignore[arg-type]
            parent_conversation_id="conv-depth-e2e",
        )
        executor = RemoteBatchExecutor(caller, max_call_depth=limit)
        outcomes = await executor.run_batch(
            [
                RemoteDelegation(
                    tool_call_id="tc-1",
                    agent_id=_AGENT_ID,
                    arguments={"query": "回报路径"},
                    parent_path=parent_path,
                )
            ],
            parent_context_id="conv-depth-e2e",
            call_depth=len(parent_path),
        )
    outcome = outcomes["tc-1"]
    echo = {}
    if outcome.content:
        try:
            echo = json.loads(outcome.content)
        except ValueError:
            echo = {"raw": outcome.content}
    return {
        "parent_path": list(parent_path),
        "depth_sent": len(parent_path),
        "limit": limit,
        "outcome": outcome.outcome,
        "skip_reason": outcome.skip_reason,
        "echo": echo,
        "expected_echo_path": list(parent_path) + [_AGENT_ID],
    }
