"""Deterministic MetricEvaluator — inherits openjiuwen MetricEvaluator."""

from __future__ import annotations

import json
from typing import Any

from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase
from openjiuwen.agent_evolving.evaluator.evaluator import (
    MetricEvaluator as _UpstreamMetricEvaluator,
)
from openjiuwen.agent_evolving.evaluator.evaluator import _agg_score

from evo_agent.evaluator.metrics.base import BatchMetric
from evo_agent.evaluator.metrics.batch import BatchMetricAggregator


class MetricEvaluator(_UpstreamMetricEvaluator):  # type: ignore[misc]
    """Unified deterministic evaluator.

    Inherits openjiuwen MetricEvaluator, reuses score aggregation and per_metric output.
    Deterministic Metrics (exact_match / normalized_exact_match) require a non-null
    ``expected_result``.

    This evaluator retains OpenJiuwen's explicit ``case``/``predict`` interface.
    Conversation-level ``EvaluationInput`` has no standalone prediction and is
    therefore evaluated by ``LLMEvaluator``.

    Batch aggregation (extension, not replacement of ``_mean_score``):
    ``batch_metrics`` + ``batch_score`` configure a *coexisting* batch-level
    score (micro F1/ACC/precision/recall). ``aggregate_score()`` produces it;
    callers (e.g. trainer validation) choose between ``_mean_score`` (mean,
    the default) and ``aggregate_score`` per their config. Per-case
    ``evaluate``/``batch_evaluate`` are unchanged — the optimizer's bad/good
    split still uses per-case ``EvaluatedCase.score``.
    """

    def __init__(
        self,
        metrics: Any,
        aggregate: str = "mean",
        *,
        batch_metrics: list[BatchMetric] | None = None,
        batch_score: str = "",
    ) -> None:
        super().__init__(metrics, aggregate)
        # batch_metrics and batch_score must be configured together (both set
        # or both empty); the factory enforces this, but guard here too.
        if bool(batch_metrics) != bool(batch_score):
            raise ValueError(
                "batch_metrics and batch_score must be configured together "
                "(both set, or both empty)."
            )
        self._batch_metrics = list(batch_metrics) if batch_metrics else None
        self._batch_score = batch_score

    def aggregate_score(self, evaluated: list[EvaluatedCase]) -> float:
        """Batch-level aggregate score — coexists with ``_mean_score`` (mean).

        Runs ``BatchMetricAggregator`` over the evaluated cases and returns the
        configured ``batch_score`` key (e.g. F1/ACC). NOT a replacement for
        ``_mean_score``: callers choose which to use as the validation score
        (default = ``_mean_score``). Raises ``ValueError`` if batch aggregation
        is not configured, and ``KeyError`` if ``batch_score`` names a key the
        aggregate did not produce (fail-fast over silent fallback).
        """
        if not self._batch_metrics or not self._batch_score:
            raise ValueError(
                "aggregate_score requires configured batch_metrics + batch_score; "
                "use _mean_score for the per-case mean instead."
            )
        agg = BatchMetricAggregator(self._batch_metrics).run(evaluated)
        if self._batch_score not in agg:
            raise KeyError(
                f"batch_score {self._batch_score!r} not in batch aggregate "
                f"(available: {sorted(agg)!r})"
            )
        return agg[self._batch_score]

    @property
    def batch_score(self) -> str:
        """The configured batch-score key (empty = batch aggregation disabled)."""
        return self._batch_score

    def evaluate(self, case: Case, predict: dict[str, Any]) -> EvaluatedCase:
        """Evaluate a single case.

        Extracts ``expected_result`` from ``case.label`` and passes it to each Metric.
        Deterministic Metrics require ``expected_result`` to be non-null.

        Raises:
            ValueError: when ``expected_result`` is None.
        """
        expected_result = case.label.get("expected_result")

        if expected_result is None:
            raise ValueError("MetricEvaluator requires expected_result")

        evaluated = EvaluatedCase(case=case, answer=predict)
        per_metric: dict[str, float] = {}
        scores: list[float] = []

        for metric in self._metrics:
            out = metric.compute(
                predict,
                expected_result,
                question=case.inputs,
                case=case,
            )
            if isinstance(out, dict):
                reason = getattr(out, "reason", "")
                attributed_skill = getattr(out, "attributed_skill", "")
                is_pass = getattr(out, "is_pass", True)
                if reason or attributed_skill:
                    evaluated.reason = json.dumps(
                        {
                            "reason": reason,
                            "is_pass": is_pass,
                            "attributed_skill": attributed_skill,
                        },
                        ensure_ascii=False,
                    )
                for k, v in out.items():
                    vf = self._safe_convert(v)
                    per_metric[k] = vf
                    scores.append(vf)
            else:
                score = self._safe_convert(out)
                per_metric[metric.name] = score
                scores.append(score)

        evaluated.score = _agg_score(scores, self._aggregate)
        evaluated.per_metric = per_metric if per_metric else None
        return evaluated
