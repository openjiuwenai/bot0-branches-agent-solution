# coding: utf-8

"""Task 编排层状态与转移守卫（FEAT-008 §2.3；框架中立，纯逻辑）。

职责边界（L1 + FEAT-008 §4.4）：**权威 Task 生命周期由 A2A SDK 承载**（实际 Task.status.state
持久化在 a2a-sdk/TaskStore）。本模块是**编排层的转移合法性守卫**——挂起/续接前先判 WORKING↔
INPUT_REQUIRED、→终态是否合法，非另设一套并行状态存储。状态取值语义对齐 A2A TaskState，但
不 import a2a-sdk（domain 框架中立，映射由 adapter 承担）。
"""
from __future__ import annotations

from enum import Enum


class TaskState(str, Enum):
    """编排层 Task 状态（语义对齐 A2A TaskState，命名去 TASK_STATE_ 前缀）。"""

    SUBMITTED = "SUBMITTED"
    WORKING = "WORKING"
    INPUT_REQUIRED = "INPUT_REQUIRED"
    AUTH_REQUIRED = "AUTH_REQUIRED"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELED = "CANCELED"
    REJECTED = "REJECTED"


_TERMINAL = frozenset(
    {TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELED, TaskState.REJECTED}
)

# 合法转移（编排层守卫）：多轮 WORKING↔INPUT_REQUIRED（FEAT-008 §4.2）；非终态可入任一终态。
_NON_TERMINAL_TO_TERMINAL = frozenset(_TERMINAL)
_TRANSITIONS: dict[TaskState, frozenset[TaskState]] = {
    TaskState.SUBMITTED: frozenset({TaskState.WORKING, TaskState.REJECTED}) | _NON_TERMINAL_TO_TERMINAL,
    TaskState.WORKING: frozenset({TaskState.INPUT_REQUIRED, TaskState.AUTH_REQUIRED}) | _NON_TERMINAL_TO_TERMINAL,
    TaskState.INPUT_REQUIRED: frozenset({TaskState.WORKING}) | _NON_TERMINAL_TO_TERMINAL,
    TaskState.AUTH_REQUIRED: frozenset({TaskState.WORKING}) | _NON_TERMINAL_TO_TERMINAL,
}


def is_terminal(state: TaskState) -> bool:
    """终态：COMPLETED/FAILED/CANCELED/REJECTED（不可再转移）。"""
    return state in _TERMINAL


def check_transition(src: TaskState, dst: TaskState) -> bool:
    """转移合法性：终态不可外出；WORKING↔INPUT_REQUIRED 多轮；非终态可入终态。"""
    if is_terminal(src):
        return False  # 终态不可再转移（幂等/防抢占的基石，§4.3）
    return dst in _TRANSITIONS.get(src, frozenset())
