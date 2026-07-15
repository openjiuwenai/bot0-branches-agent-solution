"""Batch collection metric tests — SetOverlapBatchMetric + BatchMetricAggregator.

Micro-averaged set-overlap: accumulate per-case TP/FP/FN, then P/R/F1/ACC.
``EvaluatedCase.answer`` is always a dict (upstream), so the prediction
collection lives under ``predict_key`` (default ``"answer"``); the label is used
as-is by default (``label_key=None``), appropriate for a bare-list
``expected_result``.
"""

from __future__ import annotations

from typing import Any

import pytest
from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase

from evo_agent.evaluator.metrics.base import BatchMetric
from evo_agent.evaluator.metrics.batch import BatchMetricAggregator, SetOverlapBatchMetric


def _ec(pred_items: list[str], label_items: list[str]) -> EvaluatedCase:
    """Build an EvaluatedCase: predict under 'answer' key, label as bare list."""
    return EvaluatedCase(
        case=Case(inputs={"t": "q"}, label={"expected_result": label_items}),
        answer={"answer": pred_items},
    )


class TestSetOverlapBatchMetric:
    def test_name(self) -> None:
        assert SetOverlapBatchMetric().name == "set_overlap"

    def test_micro_partial_overlap(self) -> None:
        m = SetOverlapBatchMetric()
        m.reset()
        m.accumulate({"answer": ["北京"]}, ["北京"])  # TP=1
        m.accumulate({"answer": ["上海", "成都"]}, ["广州"])  # FP=2 FN=1
        m.accumulate({"answer": []}, ["深圳"])  # FN=1
        agg = m.aggregate()
        # TP=1 FP=2 FN=2 → P=R=1/3, F1=1/3, ACC=1/5
        assert agg["precision"] == pytest.approx(1 / 3)
        assert agg["recall"] == pytest.approx(1 / 3)
        assert agg["f1"] == pytest.approx(1 / 3)
        assert agg["accuracy"] == pytest.approx(1 / 5)
        assert agg["score"] == agg["f1"]

    def test_all_correct_is_one(self) -> None:
        m = SetOverlapBatchMetric()
        m.reset()
        m.accumulate({"answer": ["北京", "上海"]}, ["北京", "上海"])
        agg = m.aggregate()
        assert agg["precision"] == 1.0
        assert agg["recall"] == 1.0
        assert agg["f1"] == 1.0
        assert agg["accuracy"] == 1.0

    def test_all_wrong_is_zero(self) -> None:
        m = SetOverlapBatchMetric()
        m.reset()
        m.accumulate({"answer": ["上海"]}, ["北京"])  # TP=0 FP=1 FN=1
        agg = m.aggregate()
        assert agg["precision"] == 0.0
        assert agg["recall"] == 0.0
        assert agg["f1"] == 0.0
        assert agg["accuracy"] == 0.0

    def test_empty_batch_is_zero_no_div(self) -> None:
        m = SetOverlapBatchMetric()
        m.reset()
        agg = m.aggregate()
        assert agg["f1"] == 0.0
        assert agg["accuracy"] == 0.0

    def test_reset_clears_state(self) -> None:
        m = SetOverlapBatchMetric()
        m.accumulate({"answer": ["x"]}, ["x"])  # TP=1
        m.reset()
        assert m.aggregate()["f1"] == 0.0

    def test_label_key_extraction(self) -> None:
        # Both predict and label wrapped under "answer"; extract both sides.
        m = SetOverlapBatchMetric(predict_key="answer", label_key="answer")
        m.reset()
        m.accumulate({"answer": ["北京"]}, {"answer": ["北京", "上海"]})  # TP=1 FP=0 FN=1
        agg = m.aggregate()
        assert agg["recall"] == pytest.approx(0.5)
        assert agg["precision"] == 1.0


class TestBatchMetricAggregator:
    def test_requires_at_least_one_metric(self) -> None:
        with pytest.raises(ValueError, match="at least one"):
            BatchMetricAggregator([])

    def test_run_merges_aggregate(self) -> None:
        cases = [
            _ec(["北京"], ["北京"]),
            _ec(["上海", "成都"], ["广州"]),
            _ec([], ["深圳"]),
        ]
        agg = BatchMetricAggregator([SetOverlapBatchMetric()]).run(cases)
        assert agg["f1"] == pytest.approx(1 / 3)
        assert agg["accuracy"] == pytest.approx(1 / 5)
        assert "score" in agg

    def test_run_resets_between_invocations(self) -> None:
        cases = [_ec(["北京"], ["北京"])]
        agg = BatchMetricAggregator([SetOverlapBatchMetric()])
        first = agg.run(cases)
        second = agg.run([])  # fresh run, no accumulation carried over
        assert first["f1"] == 1.0
        assert second["f1"] == 0.0

    def test_key_collision_last_wins_and_warns(self, caplog: pytest.LogCaptureFixture) -> None:
        class _A:
            name = "a"

            def reset(self) -> None:
                pass

            def accumulate(self, prediction: Any, label: Any, **kwargs: Any) -> None:
                pass

            def aggregate(self) -> dict[str, float]:
                return {"f1": 0.5, "score": 0.5}

        class _B:
            name = "b"

            def reset(self) -> None:
                pass

            def accumulate(self, prediction: Any, label: Any, **kwargs: Any) -> None:
                pass

            def aggregate(self) -> dict[str, float]:
                return {"f1": 0.9, "score": 0.9}

        agg = BatchMetricAggregator([_A(), _B()])
        with caplog.at_level("WARNING"):
            result = agg.run([_ec([], [])])
        assert result["f1"] == 0.9  # last wins
        assert any("collision" in rec.message for rec in caplog.records)

    def test_protocol_membership(self) -> None:
        # SetOverlapBatchMetric satisfies the BatchMetric Protocol (duck-typed).
        assert isinstance(SetOverlapBatchMetric(), BatchMetric)
