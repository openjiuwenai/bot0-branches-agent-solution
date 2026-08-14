"""agent_judge schemas + validator 单元测试。"""

from __future__ import annotations

import math

from evo_agent.evaluator.agent_judge.schemas import (
    AggregatorOutput,
    DimensionJudgment,
    SkillAttribution,
    aggregator_output_validator,
    dimension_judgment_json_schema,
)
from evo_agent.llm.structured_output import ValidationResult


class TestDimensionJudgment:
    def test_defaults(self) -> None:
        j = DimensionJudgment(dimension="safety", score=0.8)
        assert j.reasoning == ""
        assert j.dimension == "safety"

    def test_score_is_plain_float(self) -> None:
        # no ge/le constraint — clamping is the consumer's job
        j = DimensionJudgment(dimension="x", score=1.5)
        assert j.score == 1.5


class TestSkillAttribution:
    def test_defaults(self) -> None:
        a = SkillAttribution(skill_name="s")
        assert a.usage_status == "unknown"
        assert a.impact == "none"
        assert a.reason == ""


class TestAggregatorOutput:
    def test_empty_attributions_default(self) -> None:
        out = AggregatorOutput(overall_score=0.5, attribution_status="completed")
        assert out.skill_attributions == []
        assert out.attribution_error is None


class TestJsonSchema:
    def test_dimension_schema_shape(self) -> None:
        schema = dimension_judgment_json_schema()
        assert schema["type"] == "object"
        assert set(schema["required"]) == {"dimension", "score", "reasoning"}
        assert schema["additionalProperties"] is False
        props = schema["properties"]
        assert props["score"]["minimum"] == 0
        assert props["score"]["maximum"] == 1


class TestAggregatorValidator:
    def test_valid_completed(self) -> None:
        result = aggregator_output_validator(
            {
                "overall_score": 0.8,
                "skill_attributions": [{"skill_name": "s", "impact": "positive"}],
                "attribution_status": "completed",
            }
        )
        assert result.ok is True

    def test_valid_failed_status(self) -> None:
        result = aggregator_output_validator(
            {"overall_score": 0.5, "attribution_status": "failed", "attribution_error": "x"}
        )
        assert result.ok is True

    def test_bool_score_rejected(self) -> None:
        result = aggregator_output_validator(
            {"overall_score": True, "attribution_status": "completed"}
        )
        assert isinstance(result, ValidationResult)
        assert result.ok is False

    def test_non_finite_score_rejected(self) -> None:
        result = aggregator_output_validator(
            {"overall_score": math.nan, "attribution_status": "completed"}
        )
        assert result.ok is False

    def test_bad_status_rejected(self) -> None:
        result = aggregator_output_validator({"overall_score": 0.5, "attribution_status": "maybe"})
        assert result.ok is False

    def test_attributions_not_list_rejected(self) -> None:
        result = aggregator_output_validator(
            {"overall_score": 0.5, "attribution_status": "completed", "skill_attributions": "x"}
        )
        assert result.ok is False

    def test_attribution_missing_skill_name_rejected(self) -> None:
        result = aggregator_output_validator(
            {
                "overall_score": 0.5,
                "attribution_status": "completed",
                "skill_attributions": [{"impact": "positive"}],
            }
        )
        assert result.ok is False

    def test_missing_status_rejected(self) -> None:
        # attribution_status is required (not in allowed defaults)
        result = aggregator_output_validator({"overall_score": 0.5})
        assert result.ok is False
