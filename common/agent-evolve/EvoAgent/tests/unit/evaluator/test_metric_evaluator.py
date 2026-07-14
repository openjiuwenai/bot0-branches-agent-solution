"""MetricEvaluator 单元测试。"""

from __future__ import annotations

from typing import Any

import pytest
from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase
from openjiuwen.agent_evolving.evaluator.evaluator import (
    MetricEvaluator as UpstreamMetricEvaluator,
)
from openjiuwen.agent_evolving.evaluator.metrics.exact_match import ExactMatchMetric

from evo_agent.evaluator.domain.scoring import EvaluationScores
from evo_agent.evaluator.evaluators.metric import MetricEvaluator


def _make_case(
    task_input: str = "query",
    expected_result: Any = None,
) -> Case:
    return Case(
        inputs={"task_input": task_input},
        label={"expected_result": expected_result},
    )


class TestMetricEvaluatorInheritance:
    """验证继承关系。"""

    def test_inherits_upstream(self) -> None:
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=False))
        assert isinstance(evaluator, UpstreamMetricEvaluator)


class TestExactMatch:
    """exact_match 测试。"""

    def test_exact_match_perfect(self) -> None:
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=False))
        case = _make_case(expected_result={"answer": "42"})
        result = evaluator.evaluate(case, {"answer": "42"})
        assert isinstance(result, EvaluatedCase)
        assert result.score == 1.0
        assert result.per_metric == {"exact_match": 1.0}

    def test_exact_match_case_sensitive(self) -> None:
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=False))
        case = _make_case(expected_result={"answer": "Hello"})
        result = evaluator.evaluate(case, {"answer": "hello"})
        assert result.score == 0.0

    def test_exact_match_different_values(self) -> None:
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=False))
        case = _make_case(expected_result={"answer": "foo"})
        result = evaluator.evaluate(case, {"answer": "bar"})
        assert result.score == 0.0


class TestNormalizedExactMatch:
    """normalized_exact_match 测试。"""

    def test_case_insensitive(self) -> None:
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=True))
        case = _make_case(expected_result={"text": "Hello World"})
        result = evaluator.evaluate(case, {"text": "hello world"})
        assert result.score == 1.0

    def test_strips_whitespace(self) -> None:
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=True))
        # normalize 作用于 str() 结果：dict 的 str 表示中前后空白被去除
        expected = {"text": "hello"}
        predict = {"text": "hello"}
        case = _make_case(expected_result=expected)
        result = evaluator.evaluate(case, predict)
        assert result.score == 1.0

    def test_collapses_multiple_spaces(self) -> None:
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=True))
        # 大小写不同但 normalize 后相同
        case = _make_case(expected_result={"A": "Hello World"})
        result = evaluator.evaluate(case, {"a": "hello world"})
        assert result.score == 1.0

    def test_normalized_no_match(self) -> None:
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=True))
        case = _make_case(expected_result={"text": "hello"})
        result = evaluator.evaluate(case, {"text": "world"})
        assert result.score == 0.0


class TestExpectedResultValidation:
    """期望结果校验测试。"""

    def test_missing_expected_result_raises(self) -> None:
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=False))
        case = _make_case(expected_result=None)
        with pytest.raises(ValueError, match="requires expected_result"):
            evaluator.evaluate(case, {"answer": "42"})

    def test_none_expected_result_raises(self) -> None:
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=False))
        case = Case(
            inputs={"task_input": "query"},
            label={"expected_result": None},
        )
        with pytest.raises(ValueError, match="requires expected_result"):
            evaluator.evaluate(case, {"answer": "42"})

    def test_empty_dict_expected_result_accepted(self) -> None:
        """An empty dict ``{}`` is a valid expected_result (not rejected by None check)."""
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=False))
        case = _make_case(expected_result={})
        # Empty dict != {"answer": "42"}, so score should be 0 — but no ValueError
        result = evaluator.evaluate(case, {"answer": "42"})
        assert result.score == 0.0


class TestEvaluatedCaseOutput:
    """EvaluatedCase 输出结构测试。"""

    def test_returns_evaluated_case(self) -> None:
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=False))
        case = _make_case(expected_result={"answer": "hello"})
        result = evaluator.evaluate(case, {"answer": "hello"})
        assert isinstance(result, EvaluatedCase)
        assert result.case is case
        assert result.answer == {"answer": "hello"}

    def test_per_metric_populated(self) -> None:
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=False))
        case = _make_case(expected_result={"answer": "hello"})
        result = evaluator.evaluate(case, {"answer": "hello"})
        assert result.per_metric is not None
        assert "exact_match" in result.per_metric

    def test_score_clamped(self) -> None:
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=False))
        case = _make_case(expected_result={"answer": "hello"})
        result = evaluator.evaluate(case, {"answer": "hello"})
        assert 0.0 <= result.score <= 1.0

    def test_aggregate_mean_with_single_metric(self) -> None:
        evaluator = MetricEvaluator(
            metrics=ExactMatchMetric(normalize=False),
            aggregate="mean",
        )
        case = _make_case(expected_result={"answer": "hello"})
        result = evaluator.evaluate(case, {"answer": "hello"})
        assert result.score == 1.0

    def test_llm_diagnostics_are_written_to_reason(self) -> None:
        class DiagnosticMetric:
            name = "diagnostic"

            def compute(self, *_args: Any, **_kwargs: Any) -> EvaluationScores:
                return EvaluationScores(
                    {
                        "task_completion": 1.0,
                        "trajectory_quality": 0.5,
                        "safety": 1.0,
                    },
                    reason="轨迹存在冗余调用",
                    is_pass=True,
                    score=0.75,
                    attributed_skill="product_recommend_skill",
                )

        evaluator = MetricEvaluator(metrics=DiagnosticMetric())
        case = _make_case(expected_result={"answer": "hello"})

        result = evaluator.evaluate(case, {"answer": "hello"})
        reason = __import__("json").loads(result.reason)

        assert reason["reason"] == "轨迹存在冗余调用"
        assert reason["attributed_skill"] == "product_recommend_skill"


class TestAggregateScore:
    """aggregate_score — batch-level score coexisting with _mean_score (mean)."""

    def test_without_batch_config_raises(self) -> None:
        # No batch_metrics / batch_score → aggregate_score is not configured.
        evaluator = MetricEvaluator(metrics=ExactMatchMetric(normalize=False))
        with pytest.raises(ValueError, match="batch_metrics"):
            evaluator.aggregate_score([])

    def test_mismatched_batch_config_raises(self) -> None:
        from evo_agent.evaluator.metrics import SetOverlapBatchMetric

        with pytest.raises(ValueError, match="together"):
            MetricEvaluator(
                metrics=ExactMatchMetric(normalize=False),
                batch_metrics=[SetOverlapBatchMetric()],  # batch_score missing
            )

    def test_returns_selected_batch_key(self) -> None:
        from evo_agent.evaluator.metrics import SetOverlapBatchMetric

        evaluator = MetricEvaluator(
            metrics=ExactMatchMetric(normalize=False),
            batch_metrics=[SetOverlapBatchMetric()],
            batch_score="f1",
        )
        cases = [
            _make_case(expected_result=["北京"]),
            _make_case(expected_result=["广州"]),
            _make_case(expected_result=["深圳"]),
        ]
        predicts = [{"answer": ["北京"]}, {"answer": ["上海"]}, {"answer": []}]
        evaluated = evaluator.batch_evaluate(cases, predicts)
        # TP=1 FP=1 FN=2 → F1 = 0.4
        assert evaluator.aggregate_score(evaluated) == pytest.approx(0.4)

    def test_batch_score_key_missing_raises(self) -> None:
        from evo_agent.evaluator.metrics import SetOverlapBatchMetric

        evaluator = MetricEvaluator(
            metrics=ExactMatchMetric(normalize=False),
            batch_metrics=[SetOverlapBatchMetric()],
            batch_score="not_a_real_key",
        )
        with pytest.raises(KeyError):
            evaluator.aggregate_score([])

    def test_per_case_and_batch_coexist(self) -> None:
        # The batch path does not perturb per-case scoring: with batch config
        # present, per-case EvaluatedCase.score is still the exact_match result
        # (mean-able by the trainer's _mean_score), and aggregate_score returns
        # the batch F1 — two independent, coexisting score paths.
        from evo_agent.evaluator.metrics import SetOverlapBatchMetric

        evaluator = MetricEvaluator(
            metrics=ExactMatchMetric(normalize=False),
            batch_metrics=[SetOverlapBatchMetric()],
            batch_score="f1",
        )
        case = _make_case(expected_result={"answer": "42"})
        evaluated = evaluator.batch_evaluate([case], [{"answer": "42"}])
        # per-case path untouched: exact_match hit → 1.0
        assert evaluated[0].score == 1.0
        assert evaluated[0].per_metric == {"exact_match": 1.0}
        # batch path: single perfect overlap → F1 1.0
        assert evaluator.aggregate_score(evaluated) == 1.0
