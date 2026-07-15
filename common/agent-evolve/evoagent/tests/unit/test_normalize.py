"""normalize_trace_to_trajectory 单元测试 — Adapter trace → StandardTrajectory 转换。"""

from __future__ import annotations

from typing import Any

from evo_agent.evaluator.domain.models import StandardTrajectory
from evo_agent.evaluator.trajectory.normalize import normalize_trace_to_trajectory


def _validate(result: dict[str, Any]) -> StandardTrajectory:
    """Assert result passes StandardTrajectory.model_validate()."""
    return StandardTrajectory.model_validate(result)


def test_string_summary_becomes_trajectory_summary() -> None:
    """Plain string summary → TrajectorySummary dict with computed counts."""
    trace = {
        "summary": "conversation about products",
        "messages": [
            {"role": "user", "content": "hi"},
            {"role": "assistant", "content": "hello"},
        ],
    }
    result = normalize_trace_to_trajectory(trace)
    traj = _validate(result)
    assert traj.summary is not None
    assert traj.summary.summary == "conversation about products"
    assert traj.summary.total_messages == 2
    assert traj.summary.total_steps == 2


def test_none_summary_stays_none() -> None:
    """No summary in trace → summary is None."""
    trace = {"messages": [{"role": "user", "content": "hi"}]}
    result = normalize_trace_to_trajectory(trace)
    traj = _validate(result)
    assert traj.summary is None


def test_dict_summary_passed_through() -> None:
    """Dict summary → used as-is."""
    trace = {
        "summary": {
            "summary": "test",
            "total_messages": 5,
            "tool_calls_used": ["search"],
            "total_steps": 5,
            "tool_calls_count": 1,
            "tokens_used": 100,
            "metadata": {},
        },
        "messages": [{"role": "user", "content": "hi"}],
    }
    result = normalize_trace_to_trajectory(trace)
    traj = _validate(result)
    assert traj.summary is not None
    assert traj.summary.tokens_used == 100


def test_flat_tool_calls_normalized_to_openai_format() -> None:
    """Flat {name, arguments} → OpenAI {id, function: {name, arguments}}."""
    trace = {
        "messages": [
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [
                    {"id": "call_1", "name": "search_products", "arguments": '{"q": "phone"}'},
                ],
            }
        ],
    }
    result = normalize_trace_to_trajectory(trace)
    traj = _validate(result)
    tc = traj.messages[0].tool_calls[0]
    assert "function" in tc
    assert tc["function"]["name"] == "search_products"
    assert tc["id"] == "call_1"


def test_openai_tool_calls_passed_through() -> None:
    """Already-OpenAI format {id, function: {name, arguments}} → unchanged."""
    trace = {
        "messages": [
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [
                    {
                        "id": "call_abc",
                        "function": {"name": "read_file", "arguments": '{"path": "/a"}'},
                    }
                ],
            }
        ],
    }
    result = normalize_trace_to_trajectory(trace)
    traj = _validate(result)
    tc = traj.messages[0].tool_calls[0]
    assert tc["function"]["name"] == "read_file"


def test_empty_messages() -> None:
    """Empty messages list → valid empty trajectory."""
    trace: dict[str, Any] = {"messages": []}
    result = normalize_trace_to_trajectory(trace)
    traj = _validate(result)
    assert traj.messages == []
    assert traj.summary is None


def test_tool_names_extracted_for_summary() -> None:
    """String summary includes tool_calls_used from assistant messages."""
    trace = {
        "summary": "test",
        "messages": [
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [
                    {
                        "id": "c1",
                        "function": {"name": "search", "arguments": "{}"},
                    },
                    {
                        "id": "c2",
                        "function": {"name": "read_file", "arguments": "{}"},
                    },
                ],
            },
            {"role": "user", "content": "next"},
        ],
    }
    result = normalize_trace_to_trajectory(trace)
    traj = _validate(result)
    assert traj.summary is not None
    assert set(traj.summary.tool_calls_used) == {"search", "read_file"}
    assert traj.summary.tool_calls_count == 2


def test_model_validate_succeeds_on_normalized_output() -> None:
    """End-to-end: normalized output passes StandardTrajectory.model_validate()."""
    trace = {
        "summary": "full conversation",
        "messages": [
            {"role": "user", "content": "recommend a phone"},
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [
                    {"id": "tc1", "name": "search", "arguments": '{"q": "phone"}'},
                ],
            },
            {"role": "tool", "name": "search", "content": "found 3 phones", "tool_call_id": "tc1"},
            {"role": "assistant", "content": "Here are 3 phones..."},
        ],
    }
    result = normalize_trace_to_trajectory(trace)
    traj = _validate(result)
    assert len(traj.messages) == 4
    assert traj.summary is not None
    assert traj.summary.total_messages == 4
