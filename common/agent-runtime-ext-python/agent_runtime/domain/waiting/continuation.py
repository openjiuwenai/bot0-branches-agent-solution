# coding: utf-8

"""续接输入与续接判定（Feat-Func-008b §2.3.2 数据类型 / §4.2 同 Task 续接；纯 domain 规则）。

ResumeInput：客户端续接输入的框架中立表示——`user_supplement`（用户补充文本）+
`recovery_point_id`（框架续跑锚点/交互 id，空=非 id 绑定路径）。adapter 把它译为
agent-core `InteractiveInput`（raw_inputs 或 user_inputs[id]）。

续接判定：仅当 Task 处于 INPUT_REQUIRED 才可续接——runtime **不校验 message 业务语义**，
合法即交回，是否满足由 Agent 判断（可再中断／失败／完成）。
"""
from __future__ import annotations

from dataclasses import dataclass, field

from agent_runtime.domain.task.state_machine import TaskState


@dataclass(frozen=True)
class ResumeInput:
    """续接输入（框架中立）。空 recovery_point_id → 非 id 绑定（raw_inputs）路径。

    `keyed_results`：**多键回灌**（FEAT-004 §4.3 单次批量回灌）——键是框架定位键
    （agent-core node_id），值是该成员的结果。与 `user_supplement` 互斥使用：
    前者是远端委派批次的结果回灌，后者是用户补充文本。二者都空即非 id 绑定路径。
    """

    user_supplement: str = ""
    recovery_point_id: str = ""
    keyed_results: dict = field(default_factory=dict)


def can_continue(state: TaskState) -> bool:
    """Task 是否可续接：仅 INPUT_REQUIRED（不含 AUTH_REQUIRED——认证续接归其自身语义）。"""
    return state is TaskState.INPUT_REQUIRED
