"""adapter 和 trajectory 模块单元测试 — Case 适配。"""

from __future__ import annotations

from evo_agent.evaluator.adapters.openjiuwen import (
    CONVERSATION_PREDICTION,
    to_case_and_placeholder,
)
from evo_agent.evaluator.domain.models import (
    EvaluationInput,
    StandardTrajectory,
    TrajectoryMessage,
)


class TestToCaseAndPlaceholder:
    """to_case_and_placeholder 测试。"""

    def test_basic_conversion(self) -> None:
        traj = StandardTrajectory(
            messages=[TrajectoryMessage(role="assistant", content="done")],
        )
        inp = EvaluationInput(trajectory=traj, skill_names=["my_skill"])
        case, prediction = to_case_and_placeholder(inp)
        assert prediction == dict(CONVERSATION_PREDICTION)
        assert "trajectory" in case.inputs
        assert case.label == {"expected_result": None}

    def test_expected_result_in_label(self) -> None:
        traj = StandardTrajectory()
        inp = EvaluationInput(
            trajectory=traj,
            expected_result={"answer": "42"},
            skill_names=["my_skill"],
        )
        case, _ = to_case_and_placeholder(inp)
        assert case.label == {"expected_result": {"answer": "42"}}

    def test_trajectory_serialized_in_inputs(self) -> None:
        traj = StandardTrajectory(
            messages=[TrajectoryMessage(role="user", content="hi")],
        )
        inp = EvaluationInput(trajectory=traj, skill_names=["my_skill"])
        case, _ = to_case_and_placeholder(inp)
        assert "trajectory" in case.inputs
        assert case.inputs["trajectory"]["messages"][0]["role"] == "user"

    def test_skill_names_passed_to_inputs(self) -> None:
        """skill_names 通过 adapter 传入 Case.inputs。"""
        traj = StandardTrajectory()
        inp = EvaluationInput(
            trajectory=traj,
            skill_names=["skill_a", "skill_b"],
        )
        case, _ = to_case_and_placeholder(inp)
        assert case.inputs["skill_names"] == ["skill_a", "skill_b"]

    def test_skill_content_not_in_inputs(self) -> None:
        """skill_content 已从 EvaluationInput 中移除，不再传入 Case.inputs。"""
        traj = StandardTrajectory()
        inp = EvaluationInput(trajectory=traj, skill_names=["my_skill"])
        case, _ = to_case_and_placeholder(inp)
        assert "skill_content" not in case.inputs

    def test_all_fields_passed_through(self) -> None:
        """所有字段同时传入时完整传递。"""
        traj = StandardTrajectory(
            messages=[TrajectoryMessage(role="assistant", content="done")],
        )
        inp = EvaluationInput(
            trajectory=traj,
            expected_result={"ok": True},
            skill_names=["my_skill"],
        )
        case, prediction = to_case_and_placeholder(inp)
        assert case.inputs["skill_names"] == ["my_skill"]
        assert case.label == {"expected_result": {"ok": True}}
        assert prediction == dict(CONVERSATION_PREDICTION)
