"""WeightScorer 单元测试 — tc 门控公式 / 加权求和 / 回退 / clamp / 注册表。"""

from __future__ import annotations

import pytest

from evo_agent.evaluator.agent_judge.schemas import DimensionJudgment
from evo_agent.evaluator.agent_judge.scorers import (
    TaskCompletionGatedScorer,
    WeightedSumScorer,
    WeightScorer,
    get_scorer,
    list_scorers,
    register_scorer,
)


def _j(dimension: str, score: float) -> DimensionJudgment:
    return DimensionJudgment(dimension=dimension, score=score, reasoning="")


class TestWeightedSumScorer:
    @staticmethod
    def test_weighted_average() -> None:
        out = WeightedSumScorer().score(
            [_j("a", 1.0), _j("b", 0.0)], weights={"a": 0.75, "b": 0.25}
        )
        assert out == pytest.approx(0.75)

    @staticmethod
    def test_missing_weight_treated_as_zero_and_renormalized() -> None:
        # b has no weight entry → weight 0; renormalize over a only
        out = WeightedSumScorer().score([_j("a", 0.5), _j("b", 1.0)], weights={"a": 1.0})
        assert out == pytest.approx(0.5)

    @staticmethod
    def test_no_positive_weight_falls_back_to_mean() -> None:
        out = WeightedSumScorer().score([_j("a", 0.4), _j("b", 0.8)], weights={})
        assert out == pytest.approx(0.6)

    @staticmethod
    def test_empty_judgments_zero() -> None:
        assert WeightedSumScorer().score([], weights={"a": 1.0}) == pytest.approx(0.0)

    @staticmethod
    def test_clamps_out_of_range_score() -> None:
        # a stray score > 1 is clamped defensively (scores should already be [0,1])
        out = WeightedSumScorer().score([_j("a", 1.5)], weights={"a": 1.0})
        assert out == pytest.approx(1.0)


class TestTaskCompletionGatedScorer:
    @staticmethod
    def test_tc_zero_gates_to_zero() -> None:
        out = TaskCompletionGatedScorer().score(
            [_j("task_completion", 0.0), _j("safety", 1.0), _j("trajectory_quality", 1.0)],
            weights={"task_completion": 0.2, "safety": 0.35, "trajectory_quality": 0.15},
        )
        assert out == pytest.approx(0.0)

    @staticmethod
    def test_tc_times_weighted_others() -> None:
        # tc=1.0; others safety=1.0(w .35), traj=0.5(w .15) → other_avg=0.85 → 0.85
        out = TaskCompletionGatedScorer().score(
            [_j("task_completion", 1.0), _j("safety", 1.0), _j("trajectory_quality", 0.5)],
            weights={"task_completion": 0.2, "safety": 0.35, "trajectory_quality": 0.15},
        )
        assert out == pytest.approx(0.85)

    @staticmethod
    def test_tc_absent_falls_back_to_weighted_sum() -> None:
        out = TaskCompletionGatedScorer().score(
            [_j("safety", 1.0), _j("trajectory_quality", 0.0)],
            weights={"safety": 0.5, "trajectory_quality": 0.5},
        )
        assert out == pytest.approx(0.5)

    @staticmethod
    def test_only_tc_present_returns_tc() -> None:
        out = TaskCompletionGatedScorer().score(
            [_j("task_completion", 0.7)], weights={"task_completion": 1.0}
        )
        assert out == pytest.approx(0.7)

    @staticmethod
    def test_tc_own_weight_not_applied_to_others() -> None:
        # tc's weight (0.2) is the gate, not a weight on others; others renormalized
        # tc=1.0, safety=1.0, traj=1.0 → other_avg=1.0 → overall=1.0
        out = TaskCompletionGatedScorer().score(
            [_j("task_completion", 1.0), _j("safety", 1.0), _j("trajectory_quality", 1.0)],
            weights={"task_completion": 0.2, "safety": 0.35, "trajectory_quality": 0.15},
        )
        assert out == pytest.approx(1.0)


class TestScorerRegistry:
    @staticmethod
    def test_default_scorers_registered() -> None:
        names = list_scorers()
        assert "weighted_sum" in names
        assert "task_completion_gated" in names

    @staticmethod
    def test_get_returns_scorer() -> None:
        s = get_scorer("task_completion_gated")
        assert isinstance(s, WeightScorer)

    @staticmethod
    def test_default_scorer_types() -> None:
        assert isinstance(get_scorer("task_completion_gated"), TaskCompletionGatedScorer)
        assert isinstance(get_scorer("weighted_sum"), WeightedSumScorer)

    @staticmethod
    def test_unknown_scorer_raises() -> None:
        with pytest.raises(ValueError, match="Unknown judge scorer"):
            get_scorer("definitely_not_a_scorer")

    @staticmethod
    def test_register_then_get() -> None:
        s = WeightedSumScorer()
        register_scorer("test_scorer_xyz", s)
        assert get_scorer("test_scorer_xyz") is s
