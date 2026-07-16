"""Tests for answer-field extraction + field exact_match."""

from __future__ import annotations

from openjiuwen.agent_evolving.dataset import Case

from evo_agent.evaluator.evaluators.metric import MetricEvaluator
from evo_agent.evaluator.factory import create_evaluator
from evo_agent.evaluator.metrics.extract import (
    AnswerFieldExtractConfig,
    extract_prediction_field,
    parse_extract_config,
)
from evo_agent.evaluator.metrics.field_exact_match import FieldExtractExactMatchMetric


def _case(expected: str) -> Case:
    return Case(
        case_id="c1",
        inputs={"query": "q"},
        label={"expected_result": expected},
    )


def _answer(payload: str) -> dict:
    return {"answer": payload}


class TestParseExtractConfig:
    def test_field_and_fields(self) -> None:
        cfg = parse_extract_config(
            {
                "strategy": "answer_tag_json_field",
                "source": "answer",
                "field": "responsibility",
                "fields": ["responsibility_type"],
                "prefer_values": ["无责", "有责"],
            }
        )
        assert cfg is not None
        assert cfg.fields == ("responsibility", "responsibility_type")
        assert cfg.prefer_values == ("无责", "有责")

    def test_missing_field_raises(self) -> None:
        try:
            parse_extract_config({"strategy": "answer_tag_json_field"})
            raise AssertionError("expected ValueError")
        except ValueError as exc:
            assert "field" in str(exc)


class TestRegexQuotedFieldExtract:
    def test_extract_responsibility_from_raw_answer(self) -> None:
        cfg = AnswerFieldExtractConfig(fields=("responsibility", "responsibility_type"))
        pred = _answer(
            "前言\n"
            '<answer>\n{"is_overdue": false, "responsibility": "有责", "reason": "x"}\n</answer>\n'
            "尾巴"
        )
        assert extract_prediction_field(pred, cfg) == "有责"

    def test_prefer_values_order(self) -> None:
        cfg = AnswerFieldExtractConfig(
            fields=("responsibility",),
            prefer_values=("无责", "有责"),
        )
        text = '{"responsibility": "有责", "alt": {"responsibility": "无责"}}'
        assert extract_prediction_field(_answer(text), cfg) == "无责"

    def test_compat_responsibility_type(self) -> None:
        cfg = AnswerFieldExtractConfig(fields=("responsibility", "responsibility_type"))
        pred = _answer('blah "responsibility_type": "无责" blah')
        assert extract_prediction_field(pred, cfg) == "无责"

    def test_missing_returns_empty(self) -> None:
        cfg = AnswerFieldExtractConfig(fields=("responsibility",))
        assert extract_prediction_field(_answer("no field here"), cfg) == ""

    def test_trailing_comma_and_noise(self) -> None:
        cfg = AnswerFieldExtractConfig(
            fields=("responsibility",),
            prefer_values=("无责", "有责"),
        )
        messy = """
一些前言
<answer>
{
  "is_overdue": false,
  "responsibility": "有责",
  "reason": "未告知",
}
</answer>
尾巴
"""
        assert extract_prediction_field(_answer(messy), cfg) == "有责"

    def test_unquoted_value_not_matched(self) -> None:
        """Only "field": "value" form is accepted."""
        cfg = AnswerFieldExtractConfig(fields=("responsibility",))
        text = "<answer>{responsibility: 无责, reason: x}</answer>"
        assert extract_prediction_field(_answer(text), cfg) == ""


class TestFieldExtractExactMatchMetric:
    def test_match_score_one(self) -> None:
        metric = FieldExtractExactMatchMetric(
            AnswerFieldExtractConfig(fields=("responsibility",)),
            normalize=False,
        )
        pred = _answer('<answer>{"responsibility": "有责"}</answer>')
        assert metric.compute(pred, "有责") == 1.0

    def test_mismatch_score_zero(self) -> None:
        metric = FieldExtractExactMatchMetric(
            AnswerFieldExtractConfig(fields=("responsibility",)),
            normalize=False,
        )
        pred = _answer('<answer>{"responsibility": "无责"}</answer>')
        assert metric.compute(pred, "有责") == 0.0

    def test_evaluator_end_to_end_via_factory(self) -> None:
        evaluator = create_evaluator(
            {
                "type": "metric",
                "metric": "exact_match",
                "extract": {
                    "strategy": "answer_tag_json_field",
                    "source": "answer",
                    "fields": ["responsibility", "responsibility_type"],
                    "prefer_values": ["无责", "有责"],
                },
            }
        )
        assert isinstance(evaluator, MetricEvaluator)
        case = _case("有责")
        ok = evaluator.evaluate(
            case,
            _answer('<answer>{"responsibility": "有责", "reason": "ok"}</answer>'),
        )
        bad = evaluator.evaluate(
            case,
            _answer('<answer>{"responsibility": "无责"}</answer>'),
        )
        assert ok.score == 1.0
        assert bad.score == 0.0

    def test_without_extract_keeps_whole_payload_match(self) -> None:
        evaluator = create_evaluator({"type": "metric", "metric": "exact_match"})
        case = _case("有责")
        scored = evaluator.evaluate(case, _answer("有责"))
        assert scored.score == 0.0
        scored_direct = evaluator.evaluate(case, {"answer": "有责"})
        assert scored_direct.score == 0.0
        from openjiuwen.agent_evolving.evaluator.metrics.exact_match import ExactMatchMetric

        assert ExactMatchMetric(normalize=False).compute("有责", "有责") == 1.0
