# coding: utf-8
# 参考宿主与部署级 E2E 装置：SPI 实现方法必须是实例方法；
# 按场景直接构造 runtime 内部状态是这一层的职责，不是越界访问。
# pylint: disable=no-self-use,add-staticmethod-or-classmethod-decorator


"""FEAT-017 部署级 E2E 的被测服务：一个带总线消费的 runtime。

## 为什么这一条不可省

前五片判据全部在进程内构造：信封是字典、投递是替身、标准入口是替身 handler。
**它们测不到「装配起来的这套东西在一个真实进程里跑得起来」**——本仓的实证是
FEAT-027 的投射轨：65 条判据全绿而产品代码里零构造，装配点没传，
整条链路在生产中一帧都不产生。

本条起一个真进程：真 uvicorn、真端口、真 HTTP 栈、真 a2a-sdk 的 `RequestHandler`，
总线投递由一个 HTTP 端点注入（`POST /bus/inject`），投影收在 `GET /bus/projections`。

## 这个替身替掉了什么，以及它测不到的那一面

**替掉的只有 broker 那一跳**：真实部署里事件从 RocketMQ／Kafka 投递进来，
而 broker 接线归宿主与 agent-bus（权威 `CL-fb7d2e87b0a9` 明禁 runtime 依赖 broker 细节）。
本服务用一个 HTTP 端点代替那一跳——**端点之后的每一段都是产品代码**：
信封解析、校验链、准入、桥接、标准入口、投影构造、发布调用。

| 走产品代码 | 被替身绕开 |
|---|---|
| 信封六步校验、准入状态机、投影构造与字段适用性 | broker 的投递与确认协议 |
| 控制面桥到 a2a-sdk 标准 `RequestHandler` 的调用 | 真实 broker 的重投与死信 |
| 装配（开关、身份、端口注入、生命周期） | —— |
| 真 HTTP 栈、真 socket、真 uvicorn | —— |

**写清楚是为了不让「E2E 全过」被读成「broker 那一跳也验过了」。**
那一跳由宿主的适配层负责，不在本 runtime 的责任范围内。
"""
from __future__ import annotations

import asyncio
import os
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, Request

from agent_runtime.bootstrap.a2a_app import create_a2a_app
from agent_runtime.bootstrap.bus_wiring import (
    BusSettings,
    build_bus_consumer,
    build_stream_reference_gate,
)
from agent_runtime.domain.bus.projection import BusProjection
from agent_runtime.domain.result import QueryChunk

_SERVICE_ID = "e2e-bus-runtime"
#: 本 runtime 在总线上的租户。E2E 脚本每次生成一个新租户并经环境变量传进来——
#: 闸门要用它查准入记录（`CL-58541c184acb`），信封校验要用它比对事件租户（`:56`／`:164`）。
#: 缺省空串表示不校验，那是嵌入式与判据里的形态。
_TENANT = os.environ.get("E2E_BUS_TENANT", "")


class BusProbeHandler:
    """被测 Agent：收到什么答什么。

    **按请求正文分派两种产出**：默认答完，问到「要输入」时产中断帧。
    **不发委派**——本条验的是总线到标准入口这一段，
    再叠上远端编排会让失败读数混进另一个特性的问题。
    """

    agent_id = _SERVICE_ID
    priority = 0

    async def stream_query(self, request: Any):
        # **按请求正文分派**：默认答完，问到「要输入」时产一条中断帧。
        # 后者用于验等待输入投影的字段面（`CL-a3a2249a79cf` 要四项，
        # 而通路修好之后跑在通路上的那条事件一度是空的）。
        text = ""
        for entry in getattr(request, "messages", None) or []:
            if isinstance(entry, dict):
                text += str(entry.get("content", ""))
        if "要输入" in text:
            yield QueryChunk.of_interrupt(
                content="请提供账户号", interaction_id="ia-e2e-1"
            )
            return
        yield QueryChunk.of_final_answer("本月账单共 12 笔")

    async def query(self, request: Any):
        chunks = [c async for c in self.stream_query(request)]
        return chunks[-1] if chunks else None

    async def start(self) -> None:
        ...

    async def stop(self) -> None:
        ...

    async def clear_session(self, session_id: str) -> None:
        ...

    def is_healthy(self) -> bool:
        return True


class _InjectableDelivery:
    """投递端口：事件经 HTTP 端点注入，**替掉的只有 broker 那一跳**。

    确认与退回都记账，供断言读取——真实 broker 的确认协议不在 runtime 责任内，
    但「runtime 调了哪个方法」是它的对外行为，必须验得到。
    """

    def __init__(self) -> None:
        self._pending: list[dict] = []
        self.acknowledged: list[str] = []
        self.rejected: list[str] = []

    def inject(self, event: dict) -> None:
        self._pending.append(event)

    async def poll(self, *, max_items: int):
        batch, self._pending = self._pending[:max_items], self._pending[max_items:]
        for item in batch:
            yield item

    async def acknowledge(self, message_id: str, *, tenant_id: str) -> None:
        self.acknowledged.append(message_id)

    async def reject(self, message_id: str, *, tenant_id: str, requeue: bool) -> None:
        self.rejected.append(message_id)


class _RecordingPublisher:
    """投影发布端口：收在内存里，供 `GET /bus/projections` 读出。

    真实部署里它把投影发回总线；那一跳同样归宿主。
    """

    def __init__(self) -> None:
        self.sent: list[BusProjection] = []

    async def publish(self, projection: BusProjection) -> None:
        self.sent.append(projection)


def build_app() -> FastAPI:
    """装配一条带总线消费的标准 runtime。

    **标准入口那个 handler 实例传给桥接**，不另建——两条入口各建一个会让
    在途流登记表分裂（权威 `CL-5fe539dfef59` 要的是同一个控制面）。

    ## 两步装配，顺序不可调换

    状态投影要观察 Task 的每一次落盘，而观察靠**包装任务存储**。包装必须发生在
    `create_a2a_app` **之前**——它建的协议库处理器会持有传进去的那个存储实例，
    之后再换 `built_app.state.task_store` 只是改了导出，执行链路上的那个没变
    （实测：E2E 通过而状态投影一条都没发）。

    故顺序是：先建投影器 → 包装存储 → 用包装后的存储建 built_app → 再把 built_app 的
    处理器交给桥接。第一步与第四步之间有环，用两次 `build_bus_consumer` 拆开
    不划算——这里直接分两段：先单独建投影器所需的三件，再整体装配。
    """
    from a2a.server.tasks import InMemoryTaskStore

    from agent_runtime.adapters.outbound.bus.memory_store import (
        InMemoryAdmissionStore,
        InMemoryProjectionStore,
    )
    from agent_runtime.adapters.outbound.bus.task_projector import TaskStateProjector

    delivery = _InjectableDelivery()
    publisher = _RecordingPublisher()
    # 三件由本函数持有，装配时原样交给 `build_bus_consumer`——**同一批实例**，
    # 否则投影器与用例层各看各的准入记录。
    admission = InMemoryAdmissionStore(capacity=64)
    projections = InMemoryProjectionStore(capacity=64)
    # **引用服务由宿主先建**：闸门要在 `create_a2a_app` 那一刻装上，
    # 而 `build_bus_consumer` 要 built_app 建好的处理器——先建它两边共用，打破顺序环。
    from agent_runtime.adapters.inbound.bus.stream_reference import (
        StreamReferenceService,
    )

    references = StreamReferenceService()
    projector = TaskStateProjector(
        admission=admission, publisher=publisher, projections=projections
    )
    # **观察者经 `task_observer` 传进去**，包装在工厂内部做——
    # 宿主换掉 `built_app.state.task_store` 只是改导出，协议库处理器持有的那个没变。
    built_app: FastAPI = create_a2a_app(
        BusProbeHandler(),
        name=_SERVICE_ID,
        description="bus e2e",
        version="1",
        task_store=InMemoryTaskStore(),
        task_observer=projector.observe,
        # **归属解析与观察者一起传**：跨租户读核验只在这一层生效
        # （`CL-080a2ff2b87f`／`:164`）。少传它时核验整个透明放行，
        # 而两侧判据都会绿——判据 `test_bus_wiring.py` 的
        # `TestTheRealAssemblyPathCarriesTenantChecking` 钉住了这条。
        task_tenant_of=projector.tenant_of_task,
        # **流引用闸门经包装件入参装上**（`CL-58541c184acb`）：路由在工厂内部
        # 就绑定了处理器，宿主拿 `built_app.state.request_handler` 再包一层对外不生效。
        request_handler_wrapper=build_stream_reference_gate(
            references=references, admissions=admission, tenant_id=_TENANT
        ),
    )

    runtime = build_bus_consumer(
        BusSettings(
            enabled=True,
            service_id=_SERVICE_ID,
            tenant_id=_TENANT,
            max_in_flight=8,
            admission_capacity=64,
        ),
        # 标准入口的执行器由 `create_a2a_app` 挂在 built_app.state 上
        # **接执行入口，不是对外服务那个**（`CL-212432c8c82b`）：
        # 后者套着等待窗口，会把消费确认拖到执行终态之后，
        # 而权威逐字禁止「等待 Agent 执行终态后才确认消费」。
        request_handler=built_app.state.execution_handler,
        delivery=delivery,
        publisher=publisher,
        admission=admission,
        projections=projections,
        projector=projector,
        # **与闸门共用同一份引用映射**：两份时闸门解析不了总线签发的引用，
        # 每一次合法订阅都被拒，而对外表现是「带了引用也订不上」。
        references=references,
    )
    # **不用 assert**：解释器带 `-O` 跑时 assert 整条被剔除，这句检查随之消失，
    # 而它守的是「开关开了但装配没出来」——那正是最需要立刻报错的情形。
    if runtime is None:
        raise RuntimeError("开关已开却没装配出总线消费")

    # **接上任务存储供补投回读**：执行期的落盘可能早于「记下租户」那一步，
    # 受理完成后回读一次当前状态把那个窗口补上（见 `project_current`）。
    projector.attach_task_store(built_app.state.task_store)

    built_app.state.bus_delivery = delivery
    built_app.state.bus_publisher = publisher
    built_app.state.bus_runtime = runtime

    # **包住既有 lifespan，不用 `on_event`**：`create_a2a_app` 已经挂了
    # `runtime_lifespan`（处理器启停、在途流排水），而 FastAPI 里 `lifespan`
    # 参数一旦给出，`on_event` 注册的钩子整个不生效——实测过：注入的事件
    # 一条都没被消费，而日志上看不出任何异常。
    #
    # **这正是宿主接总线消费的正确形态**：总线的启停要嵌进宿主既有的生命周期，
    # 不是另起一套。
    _inner_lifespan = built_app.router.lifespan_context

    @asynccontextmanager
    async def _lifespan(scope: FastAPI):
        async with _inner_lifespan(scope):
            await runtime.lifecycle.start()
            try:
                yield
            finally:
                await runtime.lifecycle.stop()

    built_app.router.lifespan_context = _lifespan

    @built_app.post("/bus/inject")
    async def _inject(request: Request):
        """注入一条总线事件。**替掉的只有 broker 那一跳。**"""
        delivery.inject(await request.json())
        # 让消费循环跑一轮——真实部署里 broker 主动投递，这里让出即可
        await asyncio.sleep(0.15)
        return {"injected": True}

    @built_app.get("/bus/projections")
    async def _projections():
        """读出已发布的投影。"""
        return {
            "projections": [
                {
                    "eventType": p.event_type,
                    "taskId": p.task_id,
                    "tenantId": p.tenant_id,
                    "correlationId": p.correlation_id,
                    "traceId": p.trace_id,
                    "streamRef": p.stream_ref,
                    "errorCode": p.error_code,
                    # **载荷也要投出来**：漏掉它时，脚本读到的每条投影都「结构正常」，
                    # 而 `CL-a3a2249a79cf` 要求的那两项字段在读数里根本不存在——
                    # 这一层的漏投与产品那一层的不带，症状逐字节相同。
                    "inlinePayload": dict(p.inline_payload or {}),
                }
                for p in publisher.sent
            ],
            "acknowledged": delivery.acknowledged,
            "rejected": delivery.rejected,
        }

    @built_app.get("/bus/resolve")
    async def _resolve(ref: str, tenant: str, task: str):
        """用装配产物解析流引用——宿主校验 HTTP 订阅时走的正是这条路。"""
        return {
            "ok": runtime.stream_references.resolve(ref, tenant_id=tenant, task_id=task)
        }

    @built_app.get("/health")
    async def _health():
        return {"status": "ok"}

    return built_app


app: FastAPI = build_app()
