"""EvaluationInput, StandardTrajectory, and LLMEvaluationOutput model unit tests."""

from __future__ import annotations

import pytest
from pydantic import ValidationError

from evo_agent.evaluator.domain.models import (
    EvaluationInput,
    EvaluationStep,
    EvaluationTrajectory,
    GoalGenerationInput,
    GoalGenerationOutput,
    LLMEvaluationOutput,
    StandardTrajectory,
    TrajectoryMessage,
    TrajectorySummary,
)


class TestTrajectoryMessage:
    """TrajectoryMessage model tests."""

    def test_minimal_fields(self) -> None:
        msg = TrajectoryMessage(role="user")
        assert msg.role == "user"
        assert msg.content is None
        assert msg.name is None
        assert msg.tool_calls == []
        assert msg.tool_call_id is None
        assert msg.reasoning_content is None
        assert msg.metadata == {}

    def test_all_fields(self) -> None:
        msg = TrajectoryMessage(
            role="assistant",
            content="hello",
            name="bot",
            tool_calls=[{"id": "tc1", "function": {"name": "search"}}],
            tool_call_id="tc1",
            reasoning_content="thinking...",
            metadata={"step": 1},
        )
        assert msg.role == "assistant"
        assert msg.content == "hello"
        assert len(msg.tool_calls) == 1

    def test_role_required(self) -> None:
        with pytest.raises(ValidationError):
            TrajectoryMessage()  # type: ignore[call-arg]


class TestTrajectorySummary:
    """TrajectorySummary model tests."""

    def test_defaults(self) -> None:
        s = TrajectorySummary()
        assert s.total_steps == 0
        assert s.tool_calls_count == 0
        assert s.tokens_used == 0

    def test_custom_values(self) -> None:
        s = TrajectorySummary(total_steps=5, tool_calls_count=3, tokens_used=1000)
        assert s.total_steps == 5


class TestStandardTrajectory:
    """StandardTrajectory model tests."""

    def test_minimal(self) -> None:
        traj = StandardTrajectory()
        assert traj.summary is None
        assert traj.messages == []

    def test_with_messages(self) -> None:
        traj = StandardTrajectory(
            messages=[
                TrajectoryMessage(role="user", content="query"),
                TrajectoryMessage(role="assistant", content="answer"),
            ],
        )
        assert len(traj.messages) == 2

    def test_with_summary(self) -> None:
        traj = StandardTrajectory(
            summary=TrajectorySummary(total_messages=3, total_steps=2),
        )
        assert traj.summary is not None
        assert traj.summary.total_messages == 3

    def test_extra_fields_forbidden(self) -> None:
        with pytest.raises(ValidationError, match="extra_forbidden"):
            StandardTrajectory(task_input="not allowed")  # type: ignore[call-arg]


class TestEvaluationTrajectory:
    """Prompt-only simplified trajectory model tests."""

    def test_serializes_prompt_only_fields(self) -> None:
        trajectory = EvaluationTrajectory(
            steps=[
                EvaluationStep(
                    index=1,
                    role="tool_call",
                    tool_name="search",
                    tool_arguments={"query": "topic"},
                )
            ],
        )

        dumped = trajectory.model_dump()

        assert dumped["steps"][0]["tool_name"] == "search"
        assert "metadata" not in dumped["steps"][0]
        assert "reasoning_content" not in dumped["steps"][0]


class TestEvaluationInput:
    """EvaluationInput model tests."""

    def test_minimal(self) -> None:
        traj = StandardTrajectory()
        inp = EvaluationInput(trajectory=traj, skill_names=["my_skill"])
        assert inp.trajectory == traj
        assert inp.expected_result is None
        assert inp.skill_names == ["my_skill"]

    def test_with_expected_result(self) -> None:
        inp = EvaluationInput(
            trajectory=StandardTrajectory(),
            expected_result={"answer": "42"},
            skill_names=["my_skill"],
        )
        assert inp.expected_result == {"answer": "42"}

    def test_with_skill_names(self) -> None:
        inp = EvaluationInput(
            trajectory=StandardTrajectory(),
            skill_names=["skill_a", "skill_b"],
        )
        assert inp.skill_names == ["skill_a", "skill_b"]

    def test_with_all_fields(self) -> None:
        traj = StandardTrajectory(
            messages=[TrajectoryMessage(role="assistant", content="done")],
        )
        inp = EvaluationInput(
            trajectory=traj,
            expected_result={"answer": "42"},
            skill_names=["my_skill"],
        )
        assert inp.trajectory is not None
        assert inp.expected_result == {"answer": "42"}
        assert inp.skill_names == ["my_skill"]

    def test_trajectory_required(self) -> None:
        with pytest.raises(ValidationError):
            EvaluationInput(skill_names=["my_skill"])  # type: ignore[call-arg]

    def test_skill_names_required(self) -> None:
        with pytest.raises(ValidationError):
            EvaluationInput(trajectory=StandardTrajectory())  # type: ignore[call-arg]

    def test_extra_fields_forbidden(self) -> None:
        with pytest.raises(ValidationError, match="extra_forbidden"):
            EvaluationInput(
                trajectory=StandardTrajectory(),
                skill_names=["my_skill"],
                task_input="not allowed",  # type: ignore[call-arg]
            )


class TestGoalGenerationModels:
    """Goal generation domain model tests."""

    def test_goal_generation_input_requires_trajectory(self) -> None:
        traj = StandardTrajectory(
            messages=[TrajectoryMessage(role="user", content="查询余额")],
        )
        value = GoalGenerationInput(trajectory=traj)
        assert value.trajectory == traj

    def test_goal_generation_output_metadata_defaults(self) -> None:
        out = GoalGenerationOutput(goal="用户期望查询余额。")
        assert out.goal == "用户期望查询余额。"
        assert out.metadata == {}


class TestLLMEvaluationOutput:
    """LLMEvaluationOutput flat 3-dimension format model tests."""

    def test_defaults(self) -> None:
        out = LLMEvaluationOutput()
        assert out.reason == ""
        assert out.is_pass is True
        assert out.score == 0.0
        assert out.attributed_skill == ""
        assert out.task_completion == 0.0
        assert out.trajectory_quality == 0.0
        assert out.safety == 0.0

    def test_with_scores_and_attribution(self) -> None:
        out = LLMEvaluationOutput(
            reason="good",
            task_completion=1.0,
            trajectory_quality=0.8,
            safety=1.0,
            is_pass=True,
            score=0.9,
            attributed_skill="product_recommend_skill",
        )
        assert out.task_completion == 1.0
        assert out.trajectory_quality == 0.8
        assert out.safety == 1.0
        assert out.is_pass is True
        assert out.score == 0.9
        assert out.attributed_skill == "product_recommend_skill"

    def test_with_empty_attributed_skill(self) -> None:
        out = LLMEvaluationOutput(
            reason="bad trajectory",
            task_completion=0.25,
            trajectory_quality=0.5,
            safety=1.0,
            is_pass=False,
            score=0.4,
            attributed_skill="",
        )
        assert out.attributed_skill == ""
        assert out.is_pass is False
