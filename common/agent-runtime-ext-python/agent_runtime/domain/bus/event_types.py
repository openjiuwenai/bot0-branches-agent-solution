# coding: utf-8
# 超长行全在注释与文档串：权威路径必须连写才可复制跳转，Markdown 表格断行即损坏。
# 对齐上游 checkstyle 对 Javadoc 续行的同类排除；代码行宽由 ruff formatter 保证。
# pylint: disable=line-too-long


"""入站事件族与出站投影族的取值。

## 为什么事件族要单独成一个概念

投影时要按**来源族**选事件名：客户端来源用 `INVOCATION_*`，服务间来源用 `A2A_CALL_*`
（权威 `CL-e75f0f550445`）。而族**必须从准入记录里恢复，不能按当前消费的这条事件猜**
——同一个 Task 会被查询、订阅等多类事件触及，那些事件的族可能与创建它的那条不同
（客户端去查一个服务间调用产生的 Task）。按当前事件猜，会让同一个 Task 的事件序列
在两个族之间跳，而调用方是按族订阅的，跳过去的那些它根本收不到。

上游同级详设逐字写着「投影器必须从 admission 记录保存的 source family 恢复事件族，
禁止按当前消费者猜测」。
"""
from __future__ import annotations

from enum import Enum


class InboundEventType(str, Enum):
    """八类入站事件（权威 `CL-6c723b4af72f`、`CL-e62bba957344` 各四类）。

    **取消那两类在本篇内**：上游同级详设裁剪了它们，理由是「FEAT-001 当前标准入口
    没有交付 `CancelTask`」——那条裁剪自带条件，而本实现已交付取消
    （`agent_runtime/adapters/inbound/a2a/executor.py` 的 `cancel`），条件不成立。
    详见 `internal/develop/03-architecture/L2-Low-Level-Design/agent-runtime/Feat-Func-017b-bus-event-subscription-consumption.md` §1.4。
    """

    CLIENT_INVOCATION_REQUESTED = "CLIENT_INVOCATION_REQUESTED"
    CLIENT_INVOCATION_QUERY_REQUESTED = "CLIENT_INVOCATION_QUERY_REQUESTED"
    CLIENT_INVOCATION_CANCEL_REQUESTED = "CLIENT_INVOCATION_CANCEL_REQUESTED"
    CLIENT_STREAM_SUBSCRIBE_REQUESTED = "CLIENT_STREAM_SUBSCRIBE_REQUESTED"
    A2A_CALL_REQUESTED = "A2A_CALL_REQUESTED"
    A2A_CALL_QUERY_REQUESTED = "A2A_CALL_QUERY_REQUESTED"
    A2A_CALL_CANCEL_REQUESTED = "A2A_CALL_CANCEL_REQUESTED"
    A2A_STREAM_SUBSCRIBE_REQUESTED = "A2A_STREAM_SUBSCRIBE_REQUESTED"


class EventFamily(str, Enum):
    """来源族。客户端链路与服务间 A2A 链路。"""

    INVOCATION = "INVOCATION"
    A2A_CALL = "A2A_CALL"


class ProjectionKind(str, Enum):
    """出站投影的语义类别（上游 §3.3 的七类）。"""

    ACCEPTED = "ACCEPTED"
    REJECTED = "REJECTED"
    FAILED = "FAILED"
    RESPONSE = "RESPONSE"
    INPUT_REQUIRED = "INPUT_REQUIRED"
    STREAM_READY = "STREAM_READY"
    TERMINAL = "TERMINAL"


#: 创建类事件——它们会建 Task 或推进已有 Task。其余的不建。
CREATING_EVENTS = frozenset(
    {InboundEventType.CLIENT_INVOCATION_REQUESTED, InboundEventType.A2A_CALL_REQUESTED}
)

_FAMILY_BY_PREFIX = {
    "CLIENT_": EventFamily.INVOCATION,
    "A2A_": EventFamily.A2A_CALL,
}


def family_of(event_type: InboundEventType) -> EventFamily:
    """入站事件类型 → 来源族。

    **按前缀判，不逐个枚举**：新增事件类型时枚举表会漏，而前缀规则是权威定的命名法
    （`CLIENT_*` 与 `A2A_*` 两族）。前缀对不上即抛——那说明来了个本函数没见过的形态，
    静默归到某一族会让它的投影发到错的族上。
    """
    for prefix, family in _FAMILY_BY_PREFIX.items():
        if event_type.value.startswith(prefix):
            return family
    raise ValueError(f"事件类型 {event_type.value!r} 不属于任何已知来源族")


#: 流准备是**唯一不对称的一对**：客户端族叫 `INVOCATION_STREAM_READY`，
#: 服务间族叫 `A2A_STREAM_READY`——不是 `A2A_CALL_STREAM_READY`。
#: 权威 `CL-1d5ba3e4f5e4` 逐字给的就是这两个名字。按规律拼会拼错，故单列。
_STREAM_READY_NAMES = {
    EventFamily.INVOCATION: "INVOCATION_STREAM_READY",
    EventFamily.A2A_CALL: "A2A_STREAM_READY",
}


def projection_name(kind: ProjectionKind, family: EventFamily) -> str:
    """投影类别 + 来源族 → wire 上的事件名。"""
    if kind is ProjectionKind.STREAM_READY:
        return _STREAM_READY_NAMES[family]
    return f"{family.value}_{kind.value}"
