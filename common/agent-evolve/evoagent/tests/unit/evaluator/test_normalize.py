"""normalize_trace_to_trajectory 单元测试 — Adapter trace → StandardTrajectory dict。"""

from __future__ import annotations

from typing import Any

from evo_agent.evaluator.domain.models import StandardTrajectory
from evo_agent.evaluator.trajectory.normalize import normalize_trace_to_trajectory


class TestEmptyAndMinimal:
    def test_empty_trace(self) -> None:
        result = normalize_trace_to_trajectory({})
        assert result == {"summary": None, "messages": []}

    def test_no_summary_yields_none(self) -> None:
        result = normalize_trace_to_trajectory({"messages": [{"role": "user", "content": "hi"}]})
        assert result["summary"] is None
        assert len(result["messages"]) == 1

    def test_falsey_summary_yields_none(self) -> None:
        result = normalize_trace_to_trajectory({"summary": "", "messages": []})
        assert result["summary"] is None


class TestSummary:
    def test_string_summary_built_into_dict(self) -> None:
        result = normalize_trace_to_trajectory(
            {"summary": "good run", "messages": [{"role": "user", "content": "x"}]}
        )
        summary = result["summary"]
        assert summary is not None
        assert summary["summary"] == "good run"
        assert summary["total_messages"] == 1
        assert summary["total_steps"] == 1
        assert summary["tokens_used"] == 0
        assert summary["tool_calls_count"] == 0

    def test_string_summary_extracts_tool_names(self) -> None:
        messages = [
            {"role": "user", "content": "task"},
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [{"function": {"name": "search", "arguments": "{}"}}],
            },
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [{"function": {"name": "read_file", "arguments": "{}"}}],
            },
        ]
        result = normalize_trace_to_trajectory({"summary": "s", "messages": messages})
        assert result["summary"]["tool_calls_used"] == ["search", "read_file"]

    def test_dict_summary_passthrough(self) -> None:
        existing = {"summary": "x", "total_messages": 99}
        result = normalize_trace_to_trajectory({"summary": existing, "messages": []})
        assert result["summary"] == existing

    def test_int_summary_becomes_none(self) -> None:
        result = normalize_trace_to_trajectory({"summary": 42, "messages": []})
        assert result["summary"] is None

    def test_tool_calls_count_from_assistants(self) -> None:
        messages = [
            {"role": "assistant", "tool_calls": [{"function": {"name": "a"}}, {"function": {"name": "b"}}]},
            {"role": "assistant", "tool_calls": [{"function": {"name": "c"}}]},
            {"role": "user", "tool_calls": [{"function": {"name": "ignored"}}]},
        ]
        result = normalize_trace_to_trajectory({"summary": "s", "messages": messages})
        assert result["summary"]["tool_calls_count"] == 3


class TestToolCallNormalization:
    """flat {name, arguments} → OpenAI {id, function: {name, arguments}}。"""

    def test_flat_tool_call_converted(self) -> None:
        trace = {
            "messages": [
                {
                    "role": "assistant",
                    "tool_calls": [{"name": "search", "arguments": '{"q": 1}'}],
                }
            ]
        }
        result = normalize_trace_to_trajectory(trace)
        tc = result["messages"][0]["tool_calls"][0]
        assert tc["function"]["name"] == "search"
        assert tc["function"]["arguments"] == '{"q": 1}'
        assert "id" in tc

    def test_openai_format_passthrough(self) -> None:
        trace = {
            "messages": [
                {
                    "role": "assistant",
                    "tool_calls": [
                        {"id": "call_1", "function": {"name": "search", "arguments": "{}"}}
                    ],
                }
            ]
        }
        result = normalize_trace_to_trajectory(trace)
        tc = result["messages"][0]["tool_calls"][0]
        assert tc["function"]["name"] == "search"
        assert tc["id"] == "call_1"

    def test_flat_tool_call_defaults(self) -> None:
        trace = {
            "messages": [
                {
                    "role": "assistant",
                    "tool_calls": [{}],  # 缺失 name/arguments/id
                }
            ]
        }
        result = normalize_trace_to_trajectory(trace)
        tc = result["messages"][0]["tool_calls"][0]
        assert tc["function"]["name"] == ""
        assert tc["function"]["arguments"] == ""
        assert tc["id"] == ""


class TestMessageNormalization:
    def test_role_defaults_to_empty(self) -> None:
        result = normalize_trace_to_trajectory({"messages": [{"content": "x"}]})
        assert result["messages"][0]["role"] == ""

    def test_content_none_preserved(self) -> None:
        result = normalize_trace_to_trajectory({"messages": [{"role": "user", "content": None}]})
        assert result["messages"][0]["content"] is None

    def test_name_included_when_present(self) -> None:
        result = normalize_trace_to_trajectory(
            {"messages": [{"role": "tool", "content": "r", "name": "search", "tool_call_id": "t1"}]}
        )
        msg = result["messages"][0]
        assert msg["name"] == "search"
        assert msg["tool_call_id"] == "t1"

    def test_name_omitted_when_none(self) -> None:
        result = normalize_trace_to_trajectory(
            {"messages": [{"role": "tool", "content": "r"}]}
        )
        msg = result["messages"][0]
        assert "name" not in msg
        assert "tool_call_id" not in msg

    def result_validates_as_standard_trajectory(self) -> None:
        """输出能通过 StandardTrajectory.model_validate。"""
        result = normalize_trace_to_trajectory(
            {
                "summary": "summary text",
                "messages": [
                    {"role": "user", "content": "hi"},
                    {"role": "assistant", "content": "", "tool_calls": [{"name": "a"}]},
                    {"role": "tool", "tool_call_id": "x", "content": "r"},
                ],
            }
        )
        # extra="forbid" 兜底校验
        traj = StandardTrajectory.model_validate(result)
        assert len(traj.messages) == 3
        assert traj.summary is not None
        assert traj.summary.summary == "summary text"


# 实例化便于按 pytest 命名发现
class TestResultValidation:
    def test_result_validates_as_standard_trajectory(self) -> None:
        result = normalize_trace_to_trajectory(
            {
                "summary": "summary text",
                "messages": [
                    {"role": "user", "content": "hi"},
                    {"role": "assistant", "content": "", "tool_calls": [{"name": "a"}]},
                    {"role": "tool", "tool_call_id": "x", "content": "r"},
                ],
            }
        )
        traj = StandardTrajectory.model_validate(result)
        assert len(traj.messages) == 3
        assert traj.summary is not None
        assert traj.summary.summary == "summary text"
