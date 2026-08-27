# coding: utf-8

"""Task 状态 → 投影类别的映射（`CL-3a3e359ed7d7`／`:51`）。

**为什么单独一件**：进站的控制面桥要判「本次窗口内跑完了没有」，
出站的状态投影器要判「该发哪一类投影」——两者问的是同一件事。
各留一份必然漂移，而漂移的那一刻两条通路对同一个 Task 会给出互相矛盾的判断：
一边说跑完了、一边不发终态投影。

**放在适配层根而不是领域层**：它读的是协议库的状态常量，
领域层零协议依赖是本特性的硬约束（`CL-fb7d2e87b0a9`，有 AST 判据守着）。
放在这里让进站与出站都向同一个共享件依赖，而不是互相依赖
（同层横向依赖由 `tools/layer_lateral_guard.py` 阻断）。
"""
from __future__ import annotations

from typing import Any, Optional

from agent_runtime.domain.bus.event_types import ProjectionKind


#: Task 状态 → 投影类别。**逐条对齐上游 `project` 的 switch**。
#:
#: 取值用协议库的具名常量，不写裸数字——数字与状态名的对应关系随协议版本变，
#: 而写死数字时版本一升级就静默错位（映射表仍然命中，只是命中了别的状态）。
def kind_of_state(state: Any) -> Optional[ProjectionKind]:
    """状态 → 投影类别；不是投影时机时返回 `None`。

    **中间态不发**：每次状态变化都发会让调用方收到一串无意义的事件，
    而权威只要求等待输入与终态这两个转折点。
    """
    from a2a.types.a2a_pb2 import (  # noqa: PLC0415
        TASK_STATE_AUTH_REQUIRED,
        TASK_STATE_CANCELED,
        TASK_STATE_COMPLETED,
        TASK_STATE_FAILED,
        TASK_STATE_INPUT_REQUIRED,
        TASK_STATE_REJECTED,
    )

    if state in (TASK_STATE_INPUT_REQUIRED, TASK_STATE_AUTH_REQUIRED):
        return ProjectionKind.INPUT_REQUIRED
    if state in (
        TASK_STATE_COMPLETED,
        TASK_STATE_FAILED,
        TASK_STATE_CANCELED,
        TASK_STATE_REJECTED,
    ):
        return ProjectionKind.TERMINAL
    return None
