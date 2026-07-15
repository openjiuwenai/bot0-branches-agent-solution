"""Evaluation trajectory simplifier tests."""

from __future__ import annotations

from evo_agent.evaluator.domain.models import (
    StandardTrajectory,
    TrajectoryMessage,
    TrajectorySummary,
)
from evo_agent.evaluator.trajectory.simplify import simplify_trajectory


def test_simplifies_messages_tool_calls_and_skill_reads() -> None:
    trajectory = StandardTrajectory(
        messages=[
            TrajectoryMessage(
                role="user",
                content="推荐稳健理财",
                metadata={"message_id": "secret"},
            ),
            TrajectoryMessage(
                role="assistant",
                content="\n\n",
                reasoning_content="private chain of thought",
                tool_calls=[
                    {
                        "id": "read-1",
                        "type": "function",
                        "function": {
                            "name": "read_file",
                            "arguments": (
                                '{"path": "/agents/skills/product_recommend_skill/SKILL.md"}'
                            ),
                        },
                    }
                ],
            ),
            TrajectoryMessage(
                role="tool",
                tool_call_id="read-1",
                content="code=0 message='success' data=FULL SKILL DOCUMENT",
            ),
            TrajectoryMessage(
                role="assistant",
                content="推荐完成",
                tool_calls=[
                    {
                        "id": "call-1",
                        "type": "function",
                        "function": {
                            "name": "call_versatile",
                            "arguments": '{"query": "推荐理财"}',
                        },
                    }
                ],
            ),
            TrajectoryMessage(
                role="tool",
                tool_call_id="call-1",
                content='{"products": [{"riskLevel": "R2"}]}',
            ),
        ],
    )

    simplified = simplify_trajectory(trajectory)
    dumped = simplified.model_dump()

    assert [step.role for step in simplified.steps] == [
        "user",
        "assistant",
        "tool",
        "assistant",
        "tool",
    ]
    assert simplified.steps[2].tool_result == "code=0 message='success' data=FULL SKILL DOCUMENT"
    assert simplified.steps[-1].tool_result == '{"products": [{"riskLevel": "R2"}]}'
    assert "metadata" not in str(dumped)
    assert "private chain of thought" not in str(dumped)


def test_truncates_long_content_and_keeps_invalid_arguments_as_text() -> None:
    trajectory = StandardTrajectory(
        messages=[
            TrajectoryMessage(role="assistant", content="a" * 1500),
            TrajectoryMessage(
                role="assistant",
                tool_calls=[
                    {
                        "function": {
                            "name": "broken_tool",
                            "arguments": "x" * 1500,
                        }
                    }
                ],
            ),
            TrajectoryMessage(role="tool", content="z" * 2000),
        ],
    )

    simplified = simplify_trajectory(trajectory)

    assert len(simplified.steps[0].content or "") <= 1001
    assert isinstance(simplified.steps[1].tool_arguments, str)
    assert len(simplified.steps[1].tool_arguments) <= 1001
    assert len(simplified.steps[2].tool_result or "") <= 1201
    assert all(
        value.endswith("…")
        for value in (
            simplified.steps[0].content,
            simplified.steps[1].tool_arguments,
            simplified.steps[2].tool_result,
        )
        if isinstance(value, str)
    )


def test_truncates_large_structured_tool_arguments() -> None:
    trajectory = StandardTrajectory(
        messages=[
            TrajectoryMessage(
                role="assistant",
                tool_calls=[
                    {
                        "function": {
                            "name": "large_tool",
                            "arguments": '{"payload": "' + ("x" * 1500) + '"}',
                        }
                    }
                ],
            )
        ],
    )

    simplified = simplify_trajectory(trajectory)

    arguments = simplified.steps[0].tool_arguments
    assert isinstance(arguments, str)
    assert len(arguments) <= 1001
    assert arguments.endswith("…")


def test_read_file_result_content_is_preserved() -> None:
    """read_file results keep their full content for the evaluator."""
    trajectory = StandardTrajectory(
        messages=[
            TrajectoryMessage(
                role="assistant",
                content="",
                tool_calls=[
                    {
                        "id": "r1",
                        "function": {
                            "name": "read_file",
                            "arguments": '{"path": "/skills/x/SKILL.md"}',
                        },
                    }
                ],
            ),
            TrajectoryMessage(
                role="tool",
                tool_call_id="r1",
                content="code=0 message='success' data=FULL SKILL DOCUMENT",
            ),
        ],
    )
    simplified = simplify_trajectory(trajectory)
    assert simplified.steps[-1].tool_result == "code=0 message='success' data=FULL SKILL DOCUMENT"


def test_warns_when_summary_counts_do_not_match_without_mutating_input() -> None:
    trajectory = StandardTrajectory(
        summary=TrajectorySummary(
            total_messages=9,
            tool_calls_count=0,
            tool_calls_used=[],
        ),
        messages=[
            TrajectoryMessage(
                role="assistant",
                tool_calls=[{"function": {"name": "search", "arguments": "{}"}}],
            )
        ],
    )
    original = trajectory.model_dump()

    simplify_trajectory(trajectory)

    assert trajectory.model_dump() == original
