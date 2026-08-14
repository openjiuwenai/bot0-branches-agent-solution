"""SkillAggregator 单元测试 — 解析 / 未知 skill fail-fast / failed 尊重 / 聚合调用。"""

from __future__ import annotations

from typing import Any
from unittest.mock import MagicMock

import pytest

from evo_agent.evaluator.agent_judge.aggregator import (
    SkillAggregator,
    _build_aggregator_prompt,
    _parse_aggregator,
    judgments_as_text,
)
from evo_agent.evaluator.agent_judge.schemas import (
    AggregatorOutput,
    DimensionJudgment,
)
from evo_agent.evaluator.agent_judge.scorers import WeightedSumScorer
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.llm.invocation import LLMInvocationResult

_SKILLS = ["alpha_skill", "beta_skill"]


def _agg_json(
    *,
    overall_score: float = 0.8,
    attributions: list[dict[str, Any]] | None = None,
    status: str = "completed",
    error: str | None = None,
) -> str:
    import json

    return json.dumps(
        {
            "overall_score": overall_score,
            "skill_attributions": attributions or [],
            "attribution_status": status,
            "attribution_error": error,
        },
        ensure_ascii=False,
    )


class TestParseAggregator:
    def test_success(self) -> None:
        out = _parse_aggregator(
            _agg_json(
                overall_score=0.8,
                attributions=[{"skill_name": "alpha_skill", "impact": "positive", "reason": "r"}],
            ),
            skill_names=_SKILLS,
        )
        assert isinstance(out, AggregatorOutput)
        assert out.overall_score == 0.8
        assert out.attribution_status == "completed"
        assert len(out.skill_attributions) == 1
        assert out.skill_attributions[0].skill_name == "alpha_skill"
        assert out.skill_attributions[0].impact == "positive"

    def test_empty_attributions_completed_ok(self) -> None:
        out = _parse_aggregator(
            _agg_json(overall_score=0.5, attributions=[], status="completed"),
            skill_names=_SKILLS,
        )
        assert out.skill_attributions == []
        assert out.attribution_status == "completed"

    def test_failed_status_respected_not_raised(self) -> None:
        out = _parse_aggregator(
            _agg_json(overall_score=0.4, status="failed", error="unclear"),
            skill_names=_SKILLS,
        )
        assert out.attribution_status == "failed"
        assert out.attribution_error == "unclear"
        assert out.overall_score == 0.4  # score preserved

    def test_unknown_skill_raises(self) -> None:
        with pytest.raises(EvaluationError) as exc_info:
            _parse_aggregator(
                _agg_json(
                    attributions=[{"skill_name": "ghost_skill", "impact": "positive"}],
                ),
                skill_names=_SKILLS,
            )
        assert exc_info.value.category == "attribution_unknown_skill"

    def test_overall_score_not_clamped_by_parser(self) -> None:
        # clamping moved to the WeightScorer; the parser passes the LLM value through
        out = _parse_aggregator(_agg_json(overall_score=1.5), skill_names=_SKILLS)
        assert out.overall_score == 1.5

    def test_bad_json_raises(self) -> None:
        with pytest.raises(EvaluationError):
            _parse_aggregator("not json at all", skill_names=_SKILLS)

    def test_missing_status_raises(self) -> None:
        with pytest.raises(EvaluationError):
            _parse_aggregator('{"overall_score":0.5}', skill_names=_SKILLS)


class TestSkillAggregatorAggregate:
    def _make(self, text: str) -> SkillAggregator:
        invocation = MagicMock()
        invocation.invoke_sync = MagicMock(
            return_value=LLMInvocationResult(
                invocation_id="inv-1",
                text=text,
                finish_reason=None,
                input_tokens=None,
                output_tokens=None,
                transport_complete=True,
                metadata={},
            )
        )
        return SkillAggregator(invocation, scorer=WeightedSumScorer(), reserved_output_tokens=2048)

    def test_aggregate_success(self) -> None:
        # LLM emits overall_score=0.99 but the deterministic scorer wins:
        # WeightedSumScorer on safety=0.8 (weight 1.0) → 0.8.
        agg = self._make(
            _agg_json(
                overall_score=0.99,
                attributions=[{"skill_name": "alpha_skill", "impact": "positive", "reason": "r"}],
            )
        )
        judgments = [DimensionJudgment(dimension="safety", score=0.8, reasoning="r")]
        out = agg.aggregate(judgments, skill_names=_SKILLS, weights={"safety": 1.0}, case_id="c1")
        assert out.overall_score == 0.8  # scorer value, not the LLM's 0.99
        assert out.skill_attributions[0].skill_name == "alpha_skill"
        # invoke_sync called once with an evaluator-stage request
        req = invocation_req = agg._invocation.invoke_sync.call_args.args[0]
        assert req.stage == "evaluator"
        assert req.output_schema_name == "agent_judge_aggregator"
        del invocation_req  # keep linter happy

    def test_aggregate_unknown_skill_raises(self) -> None:
        agg = self._make(_agg_json(attributions=[{"skill_name": "ghost", "impact": "positive"}]))
        with pytest.raises(EvaluationError) as exc_info:
            agg.aggregate(
                [DimensionJudgment(dimension="safety", score=0.5, reasoning="r")],
                skill_names=_SKILLS,
                weights={"safety": 1.0},
                case_id="c1",
            )
        assert exc_info.value.category == "attribution_unknown_skill"

    def test_aggregate_failed_status(self) -> None:
        agg = self._make(_agg_json(overall_score=0.3, status="failed", error="unclear"))
        out = agg.aggregate(
            [DimensionJudgment(dimension="safety", score=0.3, reasoning="r")],
            skill_names=_SKILLS,
            weights={"safety": 1.0},
            case_id="c1",
        )
        assert out.attribution_status == "failed"


class TestPromptBuilding:
    def test_judgments_as_text(self) -> None:
        lines = judgments_as_text(
            [
                DimensionJudgment(dimension="safety", score=0.9, reasoning="good"),
                DimensionJudgment(dimension="task_completion", score=0.4, reasoning="weak"),
            ]
        )
        assert len(lines) == 2
        assert "safety" in lines[0] and "0.9" in lines[0]
        assert "task_completion" in lines[1]

    def test_prompt_includes_skill_names(self) -> None:
        judgments = [DimensionJudgment(dimension="safety", score=0.9, reasoning="r")]
        prompt = _build_aggregator_prompt(
            judgments, skill_names=_SKILLS, weights={"safety": 1.0}, skill_provider=None
        )
        assert "alpha_skill" in prompt and "beta_skill" in prompt
        assert "safety" in prompt
