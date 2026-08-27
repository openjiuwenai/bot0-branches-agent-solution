# coding: utf-8

"""投影事件：把 Task 状态翻译成总线响应事件的产物。

**投影只读，不写。** 它观察 Task 状态，不改写状态机——状态机归 a2a-sdk。
投影一旦能写状态，总线就成了第二个状态所有者，而权威
`Technical-AF/docs/develop/02-features/`CL-5fe539dfef59``
要的是同一个控制面。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Mapping, Optional

from agent_runtime.domain.bus.event_types import (
    EventFamily,
    ProjectionKind,
    projection_name,
)

#: 必须携带任务标识的五类（上游 §3.3 的字段表逐条）。
_TASK_ID_REQUIRED = frozenset(
    {
        ProjectionKind.ACCEPTED,
        ProjectionKind.RESPONSE,
        ProjectionKind.INPUT_REQUIRED,
        ProjectionKind.STREAM_READY,
        ProjectionKind.TERMINAL,
    }
)

#: 载荷里出现即判为流内容的键。**不得经总线发布**（`CL-81c3af3d3494`）——
#: 实时流由 A2A SSE 承载，总线上只有「流可订阅了」这个事实。
_STREAM_FLAVOURED_KEYS = frozenset({"chunks", "chunk", "delta", "sse", "frames", "event"})


@dataclass(frozen=True)
class BusProjection:
    """一条出站投影事件。"""

    kind: ProjectionKind
    family: EventFamily
    tenant_id: str
    event_id: str
    causation_message_id: str
    correlation_id: str
    trace_id: str
    occurred_at: float
    task_id: str = ""
    stream_ref: str = ""
    error_code: str = ""
    retryable: bool = False
    inline_payload: Optional[Mapping[str, Any]] = field(default=None)

    def __post_init__(self) -> None:
        if self.kind is ProjectionKind.REJECTED and self.task_id:
            raise ValueError(
                "拒绝投影不得携带任务标识——它表达「未创建 Task」，"
                "带上标识会让调用方去查一个不存在的 Task"
            )
        if self.kind in _TASK_ID_REQUIRED and not self.task_id:
            raise ValueError(f"{self.kind.value} 投影必须携带任务标识")
        if self.kind is ProjectionKind.STREAM_READY and not self.stream_ref:
            raise ValueError("流准备投影必须携带流引用")
        if self.inline_payload:
            leaked = _STREAM_FLAVOURED_KEYS & set(self.inline_payload)
            if leaked:
                raise ValueError(
                    f"投影载荷里出现流内容键 {sorted(leaked)}——"
                    "逐字流块与 SSE 帧不得经总线发布，实时流由 A2A SSE 承载"
                )

    @property
    def event_type(self) -> str:
        """wire 上的事件名，由类别与来源族拼出。"""
        return projection_name(self.kind, self.family)
