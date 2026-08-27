# coding: utf-8

"""统一执行 SPI：AgentHandler（洋葱 ports，零框架依赖）。

契约（Feat-Func-002b §2.3）：**唯一** Protocol，一接口多实现，每框架一套实现，上层
多态分发、新增框架不改上层。框架私有类型（原生流/checkpoint/hook）只存在于 adapter
实现内部，不上浮到 application/domain（差异内吸）。

权威五方法（对齐 agent-runtime-java `spec/spi/AgentHandler`）：
    query / stream_query / start / stop / clear_session

**流式 idiom 采用拉模型**（决策A）：java 权威签名为
`void streamQuery(ServeRequest, QueryStreamObserver)`（推回调，源于其语言无异步
生成器）；python 取 `stream_query(request) -> AsyncIterator[QueryChunk]`，语义逐条
对齐——`yield chunk`=onNext、`raise`/error-chunk=onError、生成器正常结束=onComplete、
**消费侧停止迭代=isCancelled**。故 **不另设 `cancel()` 方法**，
`QueryStreamObserver` 也不在 python 侧另立 port。

**取消与清理是两件事，此前被一条斜杠糊在了一起**（原文写作「消费侧停止迭代/`aclose()`
=isCancelled」）：

| | 由什么表达 | 声明类型够不够 |
|---|---|---|
| **取消** | 消费侧停止迭代 | **够**——`AsyncIterator` 的 `__anext__` 不再被调用即是取消 |
| **清理**（连接释放、上下文退出） | 消费方调 `aclose()` | 不够——`aclose()` 属 `AsyncGenerator`，不在 `AsyncIterator` 的方法面上 |

**`aclose()` 因此是可选的清理钩子，不是取消契约的一部分**：实现方返回 async generator
时它自动具备，消费方（`adapters/outbound/interruptible.py`）会在收尾处调用它驱动清理；
实现方返回自定义迭代器时它不存在，**取消照常生效**，只是清理要由实现方自己在
`__anext__` 抛 `StopAsyncIteration` 前完成。

把这一条写在这里，是因为此前它只活在适配层的一处属性探测里——
按声明类型写实现的人无从知道自己少做了什么。

**续接不新增 SPI 方法**（`CL-8ae5430ea8ab` 禁止在 FEAT-008 名义下新增 SPI）：
application 构造承载 `ResumeInput` 的续接 `ServeRequest`（`ServeRequest.for_resume`），
重走 `stream_query`；adapter 在实现内部识别续接标记并译为框架原生续接输入
（如 agent-core `InteractiveInput` + 会话恢复）。对齐 java `buildResumeRequest`。

**翻译件不进契约面**（决策A）：原生流→`QueryChunk` 的翻译是各 adapter 的内部实现
（`OutputSchemaToChunk` / `SseToChunk`），不是 port。
"""
from __future__ import annotations

from typing import AsyncIterator, Protocol, runtime_checkable

from agent_runtime.domain.context import ServeRequest
from agent_runtime.domain.result import QueryChunk, QueryResponse


@runtime_checkable
class AgentHandler(Protocol):
    """异构 Agent 统一执行入口（唯一契约面）。每框架一个实现。

    落地时显式继承本 Protocol 以获 mypy 结构符合性检查（注：`@runtime_checkable`
    的 `isinstance` 只查成员存在、不查签名，签名符合性靠 mypy）。
    """

    # —— python 本地增补（非权威；entry_points 装配面需要）——
    agent_id: str
    priority: int

    def is_healthy(self) -> bool:
        """健康/就绪（SHOULD，供 readiness 与运维观测；FEAT-002 §2.1）。"""
        ...

    # —— 权威五方法（对齐 java spec/spi/AgentHandler）——
    async def query(self, request: ServeRequest) -> QueryResponse:
        """非流式聚合：返回 `QueryResponse{result, conversation_id}`。

        实现可直接聚合自身 `stream_query`（runtime 侧不强制另设执行路径）。
        """
        ...

    def stream_query(self, request: ServeRequest) -> AsyncIterator[QueryChunk]:
        """流式执行：恒返回流（即便底层同步阻塞也包装为流，002 §2.4）。

        结果映射（**处理器只产这三值**）：原生增量→`chunk`（**含终答内容帧**）；
        需用户输入/远端继续→`interrupt`；异常/不可恢复→`error`。
        **这不是说类型集合只有三值**——`QueryChunk` 另有 `remote_agent_output`
        （对齐上游 `QueryChunk.java` 的 `QueryChunk`），它由 runtime 编排层在转发远端成员的业务输出时
        产出，不经处理器，故不在本 SPI 的产出面内。
        **成功完成不是 chunk 类型，而是流正常结束**（不得臆造 COMPLETED）。
        断流无 terminal **默认映射 `interrupt`**（FEAT-002 §5 中断检测 MUST），
        仅显式异常证据才落 `error`。

        续接：当 `request` 携带续接标记（`ServeRequest.for_resume` 构造）时，
        实现从中取 `ResumeInput` 并译为框架原生续接输入续跑。
        """
        ...

    async def start(self) -> None:
        """生命周期：装配后启动（bootstrap composition root 调用）。"""
        ...

    async def stop(self) -> None:
        """生命周期：关停时释放（排水后调用，overview §9.3）。"""
        ...

    async def clear_session(self, conversation_id: str) -> None:
        """清理该会话的框架侧状态（对齐 java `clearSession`）。"""
        ...
