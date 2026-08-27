# coding: utf-8

"""FEAT-027 部署级 E2E 的被测服务：一个会发起远端调用的 runtime。

**为什么要有这一层**：`agentEvent` 挂在 `Artifact.metadata` 上，只有走完整的
出站投射 → 领域帧 → 入站映射 → protobuf 序列化 → SSE 帧，才知道它到底有没有
到达客户端。单元判据在 protobuf 之前就断言完了——本仓有实证：单测全绿而
wire 契约仍有三处缺陷（缺 ROLE_USER、终态丢终答、端侧工具投影丢空串）。

**下游 Agent 由本进程内的替身扮演**：E2E 要验的是本 runtime 的投射面，
不是两个进程之间的网络。

## 这个替身实际替掉了什么（实测确认，不是推断）

自述曾写作「替身只替远端传输那一层，投射链路全走产品代码」——**独立复核用两发变异
证明那句话是假的**：替身持有轨并自己调 `observe_remote_task`／`project_artifact`，
于是**真实传输件 `RemoteAgentClient` 里的那两个投射调用点整段不在本条的覆盖面内**。
把它们改坏时本条照样通过（变异 M5 与 M9）。

如实的覆盖边界：

| 走产品代码 | 被替身绕开 |
|---|---|
| 投射轨的三类事件生成与判定 | 真实传输件对 protobuf 响应的翻译与投射调用点 |
| 编排层的成员帧转发与重构 | 远端 HTTP／gRPC 那一跳 |
| 入站映射、protobuf 序列化、SSE 成帧 | —— |

传输件那一段由 `agent_runtime/tests/test_agent_event_wiring.py` 的
`TestTransportLayerWiring`（TC-027-035／036，用真 `StreamResponse` 驱动）覆盖。
**写清楚是为了不让「E2E 全过」被读成「传输件那一段也验过了」。**
"""
from __future__ import annotations

from typing import Any

from fastapi import FastAPI

from agent_runtime.adapters.outbound.remote.config import RemoteAgentConfig
from agent_runtime.adapters.outbound.remote.coordinator import RemoteCallCoordinator
from agent_runtime.adapters.outbound.remote.directory import RemoteAgentDirectory
from agent_runtime.bootstrap.a2a_app import create_a2a_app
from agent_runtime.domain.remote.delegation import RemoteDelegation
from agent_runtime.domain.result import QueryChunk
from agent_runtime.ports.remote import RemoteArtifact

_AGENT_NAME = "agent-a"
_DOWNSTREAM_TASK = "task-downstream-e2e"


class _DownstreamTransport:
    """扮演下游 Agent 的传输件：产出一条状态、一帧产物、一个终答。

    **它自己不造 `agentEvent`**——标签由本 runtime 的投射轨打，这正是要验的东西。
    """

    def __init__(self, event_rail: Any = None) -> None:
        self._rail = event_rail

    async def stream(self, text: str, *, context_id: str = "", metadata: Any = None, **_: Any):
        rail = self._rail
        if rail is not None:
            for chunk in rail.observe_remote_task(_DOWNSTREAM_TASK):
                yield chunk
            for chunk in rail.observe_status(
                _DOWNSTREAM_TASK, "TASK_STATE_WORKING", parts=["下游处理中"]
            ):
                yield chunk
            projected = rail.project_artifact(
                RemoteArtifact(
                    task_id=_DOWNSTREAM_TASK,
                    artifact_id="artifact-1",
                    content="本月账单共 12 笔",
                )
            )
            if projected is not None:
                yield projected
        yield QueryChunk.of_final_answer("本月账单共 12 笔")


class EventProbeHandler:
    """产出一个委派中断帧，其余交给真实的编排层与批次执行器。

    **不自己收帧再交出**：那样会绕开编排层的成员帧转发，而标签正是在那一步被重构进
    对外帧的——把转发通道整个禁用后本 E2E 仍会通过，独立复核实测出过这处假绿。
    """

    agent_id = "agent-a"

    def __init__(self) -> None:
        #: 每个会话只委派一次。**没有它会无限递归**：批次结算后编排层把结果交回
        #: 执行链路，handler 若再产同一个委派中断帧，就又发起一轮——实测栈溢出。
        #: 真实 Agent 由模型决定要不要再委派，这里用一个标记替代那个决策。
        self._delegated: set[str] = set()

    async def stream_query(self, request: Any):
        """发起一次远端委派。

        **不自己收帧再 yield**：那样会绕开编排层的成员帧转发（`serve.py` 的 `_forward`），
        而标签正是在那一步被重构进对外帧的。上一版这么写，导致把生产转发通道整个禁用后
        本 E2E 照样通过——独立复核实测出这处假绿。

        本版产出一个**委派中断帧**，交由真实的编排层驱动批次执行器：编排层收到它之后
        走 `run_batch`，成员帧经 `_forward` 转发成对外帧。这样转发通道一断，本条即红。
        """
        session = str(getattr(request, "conversation_id", "") or "default")
        if session in self._delegated:
            yield QueryChunk.of_final_answer("本月账单共 12 笔")
            return
        self._delegated.add(session)
        yield QueryChunk.of_interrupt(
            delegation=RemoteDelegation(
                tool_call_id="call-e2e",
                agent_id="agent-b1",
                tool_name="query_bill",
                node_id="node-e2e",
            )
        )

    async def query(self, request: Any):
        chunks = [c async for c in self.stream_query(request)]
        return chunks[-1] if chunks else None

    @staticmethod
    async def start() -> None:
        ...

    @staticmethod
    async def stop() -> None:
        ...

    @staticmethod
    async def clear_session(session_id: str) -> None:
        ...

    #: 端口的完整方法面还含这两项——装配期按契约逐项校验，缺一项即拒绝注入。
    priority = 0

    @staticmethod
    def is_healthy() -> bool:
        return True


def build_batch_runner():  # noqa: ANN201
    """装配真实的批次执行器：目录指向本进程内的替身传输件。

    替身只替**远端传输**（下游 Agent 不在本机），编排层、批次执行器、成员调用件、
    投射轨、入站映射全部走产品代码。
    """
    from a2a.server.tasks import InMemoryTaskStore

    from agent_runtime.adapters.inbound.a2a.chunk_mapper import submitted_task
    from agent_runtime.bootstrap.remote_wiring import build_remote_batch_runner

    directory = RemoteAgentDirectory()
    directory.mark_available("agent-b1", "http://downstream", [{"id": "query"}])
    coordinator = RemoteCallCoordinator(
        RemoteAgentConfig(),
        directory,
        lambda url, *, event_rail=None: _DownstreamTransport(event_rail),
    )
    return build_remote_batch_runner(
        coordinator,
        task_store=InMemoryTaskStore(),
        task_factory=lambda task_id, context_id: submitted_task(
            task_id=task_id, context_id=context_id
        ),
    )


app: FastAPI = create_a2a_app(
    EventProbeHandler(), name=_AGENT_NAME, batch_runner=build_batch_runner()
)


@app.get("/health")
async def _health():
    """就绪探针。标准协议面只挂协议路由，健康探针由挂载方提供（既有 E2E 同法）。"""
    return {"status": "ok"}
