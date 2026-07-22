"""trajectory_format 单测 —— 富文本格式化 + 顾客输入提取 + 工具调用格式化。"""

from __future__ import annotations

from evo_agent.evaluator.domain.models import TrajectoryMessage
from evo_agent.evaluator.golden_data.trajectory_format import (
    _extract_customer_inputs,
    _format_history_rich,
    _format_tool_calls,
    _stringify,
    _truncate,
)


def _msg(role: str, content: object = None, tool_calls: list | None = None) -> TrajectoryMessage:
    return TrajectoryMessage(role=role, content=content, tool_calls=tool_calls or [])


def test_truncate_no_truncate_when_cap_le0() -> None:
    assert _truncate("", 10) == ""
    assert _truncate("abc", 0) == "abc"
    assert _truncate("abc", -1) == "abc"


def test_truncate_within_cap() -> None:
    assert _truncate("abc", 10) == "abc"


def test_truncate_over_cap() -> None:
    assert _truncate("abcdef", 3) == "abc…(共6字)"


def test_stringify_types() -> None:
    assert _stringify(None) == ""
    assert _stringify("x") == "x"
    assert _stringify({"a": 1}) == '{"a": 1}'
    assert _stringify([1, 2]) == "[1, 2]"


def test_format_tool_calls_openai_shape() -> None:
    tcs = [{"function": {"name": "send_email", "arguments": '{"to":"a"}'}}]
    out = _format_tool_calls(tcs)
    assert "send_email(" in out


def test_format_tool_calls_skips_non_dict() -> None:
    assert _format_tool_calls([{"function": "x"}, "bad", None, 5]) == ""


def test_extract_customer_inputs() -> None:
    msgs = [
        _msg("assistant", "hi"),
        _msg("user", "第一句"),
        _msg("assistant", "ok"),
        _msg("user", "第二句"),
    ]
    first, turns = _extract_customer_inputs(msgs)
    assert first == "第一句"
    assert turns == ["第一句", "第二句"]


def test_extract_customer_inputs_none() -> None:
    first, turns = _extract_customer_inputs([_msg("assistant", "hi")])
    assert first == "" and turns == []


def test_format_history_rich_no_content_truncation() -> None:
    long = "x" * 2000
    h = _format_history_rich([_msg("user", long)])
    assert long in h  # content_cap=0 不截断


def test_format_history_rich_role_labels() -> None:
    h = _format_history_rich([_msg("user", "u"), _msg("assistant", "a"), _msg("tool", "t")])
    assert "顾客" in h and "Agent" in h and "工具结果" in h


def test_format_history_rich_report_dedup() -> None:
    rep = "业务报告" + "x" * 250  # > 200 触发业务报告去重
    h = _format_history_rich([_msg("tool", rep), _msg("tool", rep)])
    assert h.count("业务报告: (同前)") == 1
