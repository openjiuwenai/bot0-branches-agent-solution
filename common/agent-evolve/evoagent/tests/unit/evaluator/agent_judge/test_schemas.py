"""agent_judge schemas + validator 单元测试。"""

from __future__ import annotations

from evo_agent.evaluator.agent_judge.schemas import (
    AggregatorOutput,
    DimensionJudgment,
    SkillAttribution,
    aggregator_output_validator,
    dimension_judgment_json_schema,
)


class TestDimensionJudgment:
    @staticmethod
    def test_defaults() -> None:
        j = DimensionJudgment(dimension="safety", score=0.8)
        assert j.reasoning == ""
        assert j.dimension == "safety"

    @staticmethod
    def test_score_is_plain_float() -> None:
        # no ge/le constraint — clamping is the consumer's job
        j = DimensionJudgment(dimension="x", score=1.5)
        assert j.score == 1.5


class TestSkillAttribution:
    @staticmethod
    def test_defaults() -> None:
        a = SkillAttribution(skill_name="s")
        assert a.usage_status == "unknown"
        assert a.impact == "none"
        assert a.reason == ""


class TestAggregatorOutput:
    @staticmethod
    def test_empty_attributions_default() -> None:
        out = AggregatorOutput(overall_score=0.5, attribution_status="completed")
        assert out.skill_attributions == []
        assert out.attribution_error is None


class TestJsonSchema:
    @staticmethod
    def test_dimension_schema_shape() -> None:
        schema = dimension_judgment_json_schema()
        assert schema["type"] == "object"
        assert set(schema["required"]) == {"dimension", "score", "reasoning"}
        assert schema["additionalProperties"] is False
        props = schema["properties"]
        assert props["score"]["minimum"] == 0
        assert props["score"]["maximum"] == 1


class TestAggregatorValidator:
    @staticmethod
    def test_valid_completed() -> None:
        result = aggregator_output_validator(
            {
                "skill_attributions": [{"skill_name": "s", "impact": "positive"}],
                "attribution_status": "completed",
            }
        )
        assert result.ok is True

    @staticmethod
    def test_valid_failed_status() -> None:
        result = aggregator_output_validator(
            {
                "attribution_status": "failed",
                "attribution_error": "x",
            }
        )
        assert result.ok is True

    @staticmethod
    def test_bad_status_rejected() -> None:
        result = aggregator_output_validator(
            {
                "attribution_status": "maybe",
            }
        )
        assert result.ok is False

    @staticmethod
    def test_attributions_not_list_rejected() -> None:
        result = aggregator_output_validator(
            {
                "attribution_status": "completed",
                "skill_attributions": "x",
            }
        )
        assert result.ok is False

    @staticmethod
    def test_attribution_missing_skill_name_rejected() -> None:
        result = aggregator_output_validator(
            {
                "attribution_status": "completed",
                "skill_attributions": [{"impact": "positive"}],
            }
        )
        assert result.ok is False

    @staticmethod
    def test_missing_status_rejected() -> None:
        # attribution_status is required (not in allowed defaults)
        result = aggregator_output_validator({"skill_attributions": []})
        assert result.ok is False
