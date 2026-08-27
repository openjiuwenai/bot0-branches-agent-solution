# coding: utf-8

"""端侧工具请求与结果（FEAT-009；框架中立值对象）。

FEAT-009:Agent 执行中产生客户端本地工具调用时,runtime 挂起 Task、经响应投影把工具请求返回
client;client 执行(或拒绝/超时/失败)后按 continuation 提交 outcome,runtime 恢复原 Task。
**异常 outcome 透传(§2 MUST)**:拒绝/超时/失败/不可用/参数非法等均作工具结果回灌执行链路,
runtime 不因 outcome 表示失败就把 Task 置 FAILED——由 Agent 判断。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class ClientToolStatus(str, Enum):
    """client 本地工具执行结果分类（FEAT-009 §2 异常 outcome）。"""

    SUCCESS = "success"
    REJECTED = "rejected"  # 用户拒绝/审批未过
    UNAVAILABLE = "unavailable"  # 工具未声明/未暴露/不可用
    FAILED = "failed"  # 执行失败
    TIMEOUT = "timeout"
    INVALID_ARGS = "invalid_args"


@dataclass(frozen=True)
class ClientToolRequest:
    """投影给 client 的端侧工具请求。"""

    tool_name: str
    args: dict = field(default_factory=dict)
    call_id: str = ""

    def to_projection(self) -> dict:
        return {"client_tool": self.tool_name, "args": dict(self.args), "call_id": self.call_id}


@dataclass(frozen=True)
class ClientToolOutcome:
    """client 回传的端侧工具 outcome。所有状态均作工具结果回灌（不置 Task FAILED）。"""

    status: ClientToolStatus
    value: Any = None

    def as_tool_result(self) -> str:
        """归一为工具结果文本交回 Agent。异常 outcome 带状态前缀透传（§2）。"""
        if self.status is ClientToolStatus.SUCCESS:
            return str(self.value) if self.value is not None else ""
        return f"[{self.status.value}] {self.value if self.value is not None else ''}".rstrip()

    @classmethod
    def from_raw(cls, raw: Any) -> "ClientToolOutcome":
        """从 client continuation 回传解析:{status,value} 结构化,或裸值视为 success。"""
        if isinstance(raw, dict) and "status" in raw:
            try:
                st = ClientToolStatus(str(raw["status"]))
            except ValueError:
                st = ClientToolStatus.FAILED
            return cls(status=st, value=raw.get("value"))
        return cls(status=ClientToolStatus.SUCCESS, value=raw)
