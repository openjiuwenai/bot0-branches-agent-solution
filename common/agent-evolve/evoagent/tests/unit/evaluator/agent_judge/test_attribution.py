"""Attribution agent prompt + parse unit tests (replaces test_aggregator.py)."""

from __future__ import annotations

from typing import Any

import pytest

from evo_agent.evaluator.agent_judge.attribution import (
    build_attribution_prompt,
    judgments_as_text,
    parse_attribution_output,
)
from evo_agent.evaluator.agent_judge.schemas import DimensionJudgment
from evo_agent.evaluator.domain.scoring import EvaluationError

_SKILLS = ["alpha_skill", "beta_skill"]


def _judgments() -> list[DimensionJudgment]:
    return [
        DimensionJudgment(dimension="task_completion", score=1.0, reasoning="done"),
        DimensionJudgment(dimension="safety", score=0.5, reasoning="minor risk"),
    ]


def _attr(
    *,
    skill_name: str = "alpha_skill",
    impact: str = "positive",
    usage: str = "executed",
    reason: str = "r",
) -> dict[str, Any]:
    return {"skill_name": skill_name, "usage_status": usage, "impact": impact, "reason": reason}


def _full_data(
    *,
    attributions: list[dict[str, Any]] | None = None,
    status: str = "completed",
    error: str | None = None,
) -> dict[str, Any]:
    """Build a complete attribution output dict with required fields."""
    return {
        "skill_attributions": attributions if attributions is not None else [_attr()],
        "attribution_status": status,
        "attribution_error": error,
    }


class _FakeProvider:
    def __init__(self, content: str = "DOC") -> None:
        self._content = content

    def get_skill_content(self, name: str) -> str:
        return f"{self._content}:{name}"


class TestBuildAttributionPrompt:
    @staticmethod
    def test_inlines_judgments_and_skills() -> None:
        prompt = build_attribution_prompt(_judgments(), skill_names=_SKILLS, skill_provider=None)
        assert "task_completion: 1.0" in prompt
        assert "safety: 0.5" in prompt
        assert "alpha_skill" in prompt and "beta_skill" in prompt
        assert "attribution_status" in prompt
        # No threshold block when dimension_thresholds is None
        assert "维度阈值校验" not in prompt

    @staticmethod
    def test_no_provider_hint() -> None:
        prompt = build_attribution_prompt(_judgments(), skill_names=_SKILLS, skill_provider=None)
        assert "未提供 skill 文档源" in prompt

    @staticmethod
    def test_with_provider_inlines_docs() -> None:
        prompt = build_attribution_prompt(
            _judgments(),
            skill_names=_SKILLS,
            skill_provider=_FakeProvider(),  # type: ignore[arg-type]
        )
        assert "DOC:alpha_skill" in prompt
        assert "DOC:beta_skill" in prompt

    @staticmethod
    def test_trajectory_read_hint() -> None:
        prompt = build_attribution_prompt(_judgments(), skill_names=_SKILLS, skill_provider=None)
        assert "trajectory.jsonl" in prompt
        assert "trajectory.md" in prompt

    @staticmethod
    def test_dimension_thresholds_in_prompt() -> None:
        thresholds = {"task_completion": 0.5, "safety": 0.8}
        prompt = build_attribution_prompt(
            _judgments(),
            skill_names=_SKILLS,
            skill_provider=None,
            dimension_thresholds=thresholds,
        )
        assert "维度阈值校验" in prompt
        assert "attribution_calculator" in prompt
        assert "--thresholds" in prompt
        # The threshold values appear in the inline JSON
        assert "0.5" in prompt
        assert "0.8" in prompt

    @staticmethod
    def test_no_thresholds_no_calculator_hint() -> None:
        # Without thresholds, the prompt does not instruct the agent to call
        # the calculator script (it's only used for threshold checking).
        prompt = build_attribution_prompt(_judgments(), skill_names=_SKILLS, skill_provider=None)
        assert "--thresholds" not in prompt


class TestJudgmentsAsText:
    @staticmethod
    def test_renders_each() -> None:
        assert judgments_as_text(_judgments()) == [
            "- task_completion: 1.0 — done",
            "- safety: 0.5 — minor risk",
        ]

    @staticmethod
    def test_empty() -> None:
        assert judgments_as_text([]) == []


class TestParseAttributionOutput:
    @staticmethod
    def test_success() -> None:
        data = _full_data()
        out = parse_attribution_output(data, skill_names=_SKILLS)
        assert out.attribution_status == "completed"
        assert out.skill_attributions[0].skill_name == "alpha_skill"
        assert out.skill_attributions[0].impact == "positive"

    @staticmethod
    def test_empty_completed_valid() -> None:
        data = _full_data(attributions=[])
        out = parse_attribution_output(data, skill_names=_SKILLS)
        assert out.skill_attributions == []
        assert out.attribution_status == "completed"

    @staticmethod
    def test_failed_status_preserved() -> None:
        data = _full_data(attributions=[], status="failed", error="unsure")
        out = parse_attribution_output(data, skill_names=_SKILLS)
        assert out.attribution_status == "failed"
        assert out.attribution_error == "unsure"

    @staticmethod
    def test_unknown_skill_raises() -> None:
        data = _full_data(attributions=[_attr(skill_name="ghost")])
        with pytest.raises(EvaluationError) as exc_info:
            parse_attribution_output(data, skill_names=_SKILLS)
        assert exc_info.value.category == "attribution_unknown_skill"

    @staticmethod
    def test_bad_status_raises() -> None:
        data = _full_data(attributions=[], status="maybe")
        with pytest.raises(EvaluationError, match="failed validation"):
            parse_attribution_output(data, skill_names=_SKILLS)

    @staticmethod
    def test_missing_status_raises() -> None:
        data = {"skill_attributions": []}
        with pytest.raises(EvaluationError, match="failed validation"):
            parse_attribution_output(data, skill_names=_SKILLS)
