"""golden_data 轨迹富文本格式化（port golden_gen ``_format_turn_rich``，适配
``TrajectoryMessage``）。

builder phase1（snippet，``max_turns`` 截断）与 generator phase2（history 全量）共用
此处的 ``_format_turn_rich`` / ``_truncate`` / ``_stringify`` / ``_format_tool_calls``；
phase2 额外用 ``_format_history_rich``（全量、content 不截断）与
``_extract_customer_inputs``（提取 user 轮）。
"""

from __future__ import annotations

import json
from typing import Any

from evo_agent.evaluator.domain.models import TrajectoryMessage

__all__ = [
    "_extract_customer_inputs",
    "_format_history_rich",
    "_format_tool_calls",
    "_format_turn_rich",
    "_stringify",
    "_truncate",
]

_TOOL_ARGS_CAP = 200
_TOOL_REPORT_DEDUP_PREFIX = 200


def _truncate(text: str, cap: int) -> str:
    """cap<=0 表示不截断。"""
    if not text:
        return ""
    if cap and cap > 0 and len(text) > cap:
        return text[:cap] + f"…(共{len(text)}字)"
    return text


def _stringify(content: Any) -> str:
    if content is None:
        return ""
    if isinstance(content, str):
        return content
    return json.dumps(content, ensure_ascii=False, default=str)


def _format_tool_calls(tool_calls: list[dict[str, Any]]) -> str:
    """格式化 tool_calls（OpenAI 格式 ``{function: {name, arguments}}``）为 ``name(args)`` 串。"""
    parts: list[str] = []
    for tc in tool_calls:
        if not isinstance(tc, dict):
            continue
        fn = tc.get("function")
        if not isinstance(fn, dict):
            continue
        name = fn.get("name") or "?"
        args = _truncate(_stringify(fn.get("arguments")), _TOOL_ARGS_CAP)
        parts.append(f"{name}({args})")
    return "; ".join(parts)


def _format_turn_rich(
    message: TrajectoryMessage,
    content_cap: int,
    report_cap: int,
    seen_reports: set[str],
) -> str:
    """格式化单条 message：角色名 + content 截断 + assistant 工具调用 + tool 业务报告去重。"""
    raw_role = message.role
    if raw_role == "user":
        role = "顾客"
    elif raw_role == "assistant":
        role = "Agent"
    elif raw_role == "tool":
        role = "工具结果"
    else:
        role = raw_role or "?"

    content = _stringify(message.content).strip()
    lines = [f"  [{role}]: {_truncate(content, content_cap)}"]

    if role == "Agent" and message.tool_calls:
        lines.append(f"    工具调用: {_format_tool_calls(message.tool_calls)}")

    if role == "工具结果" and len(content) > _TOOL_REPORT_DEDUP_PREFIX:
        key = content[:_TOOL_REPORT_DEDUP_PREFIX]
        if key in seen_reports:
            lines.append("    业务报告: (同前)")
        else:
            seen_reports.add(key)
            lines.append(f"    业务报告: {_truncate(content, report_cap)}")

    return "\n".join(lines)


def _format_history_rich(messages: list[TrajectoryMessage], report_cap: int = 800) -> str:
    """全量轨迹富文本（content 不截断，``content_cap=0``）；业务报告按 report_cap 截断去重。"""
    seen: set[str] = set()
    return "\n".join(
        _format_turn_rich(m, content_cap=0, report_cap=report_cap, seen_reports=seen)
        for m in messages
    )


def _extract_customer_inputs(messages: list[TrajectoryMessage]) -> tuple[str, list[str]]:
    """从 messages 提取所有用户输入（role=user）：返回 (first, turns)。"""
    turns: list[str] = []
    for m in messages:
        if m.role == "user":
            c = _stringify(m.content).strip()
            if c:
                turns.append(c)
    first = turns[0] if turns else ""
    return first, turns
