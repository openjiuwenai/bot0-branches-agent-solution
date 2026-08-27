# coding: utf-8

"""准入记录：一次创建类调用在 runtime 侧的受理事实。

## 它不是 Task

准入记录回答的是「这个幂等键对应哪个 Task、来自哪个族」；Task 本身与它的状态
归 a2a-sdk 的状态机管。两者混成一个会让总线成为第二个状态所有者，而权威
`Technical-AF/docs/develop/02-features/`CL-5fe539dfef59``
要的是**同一个控制面**。

## 键为什么只有两项

`租户 + 幂等键`，不含消息标识（权威 `CL-9addb2134440`／`:54`）。混进消息标识会让重试
（新消息标识、同幂等键）建出第二个 Task——那正是创建幂等要防的事。

`correlationId` 与 `clientInvocationId` 都不是键：前者是链路关联字段，后者是网关侧的
关联句柄。用它们当键，会让同一次调用的多个阶段被判成重复。
"""
from __future__ import annotations

from dataclasses import dataclass, replace
from enum import Enum

from agent_runtime.domain.bus.event_types import EventFamily


class AdmissionState(str, Enum):
    """准入状态机（详设 §4.2）。"""

    RESERVED = "RESERVED"
    ADMITTED = "ADMITTED"
    REJECTED = "REJECTED"


class AdmissionOutcome(str, Enum):
    """一次预留请求的结果三分。"""

    CREATED = "CREATED"
    REUSED = "REUSED"
    CONFLICT = "CONFLICT"


@dataclass(frozen=True)
class AdmissionKey:
    """`租户 + 幂等键`。**可哈希**——它是存储的键。"""

    tenant_id: str
    idempotency_key: str


@dataclass(frozen=True)
class AdmissionRecord:
    """一条准入记录。

    字段 family：**来源族存在这里，投影时据它恢复**（`CL-e75f0f550445`）。
        不存下来就只能按当前消费的那条事件猜，而同一个 Task 会被不同族的事件触及。
    字段 request_digest：载荷的稳定摘要。同键不同摘要即冲突。
    """

    key: AdmissionKey
    task_id: str
    family: EventFamily
    correlation_id: str
    request_digest: str
    created_at: float
    #: 链路追踪标识。**状态投影要带它**（`CL-934c944b9fb2`／`:171`），而那时手上
    #: 只有准入记录——信封早已不在作用域内。上游的 `Admission` 同样存了它。
    #: 默认空是因为它是后加的字段，既有调用点里有的确实没有 trace。
    trace_id: str = ""
    state: AdmissionState = AdmissionState.RESERVED
    reject_reason: str = ""

    def admitted(self) -> "AdmissionRecord":
        """转「已受理」。**已拒绝不能再受理**——拒绝是终局。"""
        if self.state is AdmissionState.REJECTED:
            raise ValueError("已拒绝的准入记录不能转为已受理——拒绝是终局")
        return replace(self, state=AdmissionState.ADMITTED)

    def rejected(self, reason: str) -> "AdmissionRecord":
        """转「已拒绝」。**只用于准入前的确定性拒绝。**

        已受理之后不能退回：「未创建 Task 的拒绝」与「已创建后的终态拒绝」
        是两件事，调用方据它们区分「请求没被受理」与「受理了但结果是拒绝」。
        允许倒退会让后者被当前者去重试（详设 §4.4）。
        """
        if self.state is AdmissionState.ADMITTED:
            raise ValueError(
                "已受理的准入记录不能退回已拒绝——那之后的失败由 Task 终态承载"
            )
        return replace(self, state=AdmissionState.REJECTED, reject_reason=reason)

    def with_task_id(self, task_id: str) -> "AdmissionRecord":
        """校正任务标识。**只在受理时用**——标准入口是 Task 的所有者，
        它给出的标识才是权威；预留时那个是确定性派生的占位。
        """
        return replace(self, task_id=task_id)

    def outcome_for(self, request_digest: str) -> AdmissionOutcome:
        """同一个键再来一次时的结果：摘要相同即复用，不同即冲突。"""
        return (
            AdmissionOutcome.REUSED
            if request_digest == self.request_digest
            else AdmissionOutcome.CONFLICT
        )
