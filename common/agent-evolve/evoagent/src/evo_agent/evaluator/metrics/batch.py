"""Batch-level collection metrics — micro-aggregated F1/ACC/precision/recall.

Unlike per-case metrics, batch metrics accumulate confusion counts (TP/FP/FN)
across a *collection* of cases and produce a single micro aggregate. The
``SetOverlapBatchMetric`` treats each case's (prediction, label) as two item
sets and micro-averages set-overlap counts.

``BatchMetricAggregator`` runs a list of batch metrics serially over a list of
``EvaluatedCase`` and merges their aggregate dicts. Key collisions across
metrics are last-wins with a WARNING — custom batch metrics should use unique
keys to disambiguate.
"""

from __future__ import annotations

import logging
from typing import Any

from openjiuwen.agent_evolving.dataset import EvaluatedCase

from evo_agent.evaluator.metrics.base import BatchMetric, BatchMetricResult

logger = logging.getLogger(__name__)

__all__ = ["SetOverlapBatchMetric", "BatchMetricAggregator"]


class SetOverlapBatchMetric:
    """Micro-averaged set-overlap F1/ACC/precision/recall across a batch.

    ``EvaluatedCase.answer`` is always a dict (upstream constraint), so the
    prediction collection lives under a key. ``predict_key`` extracts it from
    the prediction dict (default ``"answer"``); ``label_key`` extracts the
    expected collection from the label (default ``None`` = use the label
    as-is, appropriate when ``expected_result`` is a bare list/set). Set both
    keys to match how you wrapped your collections.

    After extraction, each side is coerced to an item set
    (list/tuple/set/frozenset → items; dict → values; scalar/str → single
    item). Per case:

    - TP = |prediction ∩ label|
    - FP = |prediction − label|
    - FN = |label − prediction|

    Counts are summed across the batch, then micro-averaged:

    - precision = TP / (TP + FP)
    - recall    = TP / (TP + FN)
    - f1        = 2·P·R / (P + R)
    - accuracy  = TP / (TP + FP + FN)   (Jaccard/IoU — sets have no TN)
    - score     = f1                    (primary selectable key)

    All-zero counts yield 0.0 (never divides by zero).
    """

    name = "set_overlap"

    def __init__(
        self,
        *,
        predict_key: str | None = "answer",
        label_key: str | None = None,
    ) -> None:
        self._predict_key = predict_key
        self._label_key = label_key
        self.reset()

    def reset(self) -> None:
        self._tp = 0
        self._fp = 0
        self._fn = 0

    def accumulate(self, prediction: Any, label: Any, **kwargs: Any) -> None:
        pred_set = _to_item_set(_extract_collection(prediction, self._predict_key))
        label_set = _to_item_set(_extract_collection(label, self._label_key))
        self._tp += len(pred_set & label_set)
        self._fp += len(pred_set - label_set)
        self._fn += len(label_set - pred_set)

    def aggregate(self) -> BatchMetricResult:
        tp, fp, fn = self._tp, self._fp, self._fn
        precision = tp / (tp + fp) if (tp + fp) else 0.0
        recall = tp / (tp + fn) if (tp + fn) else 0.0
        f1 = (2 * precision * recall) / (precision + recall) if (precision + recall) else 0.0
        accuracy = tp / (tp + fp + fn) if (tp + fp + fn) else 0.0
        return {
            "precision": precision,
            "recall": recall,
            "f1": f1,
            "accuracy": accuracy,
            "score": f1,
        }


class BatchMetricAggregator:
    """Run a list of batch metrics serially over evaluated cases.

    For each batch metric: ``reset`` → ``accumulate`` once per case (predict =
    ``EvaluatedCase.answer``, label = ``case.label["expected_result"]``) →
    ``aggregate``. Results are merged into one dict; bare keys are last-wins on
    collision with a WARNING (custom metrics should namespace their keys).
    """

    def __init__(self, batch_metrics: list[BatchMetric]) -> None:
        if not batch_metrics:
            raise ValueError("BatchMetricAggregator requires at least one batch metric")
        self._batch_metrics = list(batch_metrics)

    def run(self, evaluated: list[EvaluatedCase]) -> BatchMetricResult:
        merged: BatchMetricResult = {}
        for metric in self._batch_metrics:
            metric.reset()
            for ec in evaluated:
                label = (
                    ec.case.label.get("expected_result")
                    if isinstance(ec.case.label, dict)
                    else None
                )
                metric.accumulate(ec.answer, label)
            for key, value in metric.aggregate().items():
                if key in merged:
                    logger.warning(
                        "Batch metric key %r collision: overwritten by %r; "
                        "use unique keys or namespace with the metric name.",
                        key,
                        metric.name,
                    )
                merged[key] = value
        return merged


def _extract_collection(value: Any, key: str | None) -> Any:
    """Extract the comparable collection from a side.

    ``key=None`` returns the value as-is. When ``key`` is set and the value is
    a dict, ``value[key]`` is returned (the items live under that key);
    non-dict values are returned as-is so a bare-list label still works with
    ``label_key=None``.
    """
    if key is None:
        return value
    if isinstance(value, dict):
        return value.get(key)
    return value


def _to_item_set(value: Any) -> set[str]:
    """Coerce a value into a set of stringified items for overlap counting."""
    if value is None:
        return set()
    if isinstance(value, (list, tuple, set, frozenset)):
        return {str(x) for x in value}
    if isinstance(value, dict):
        return {str(x) for x in value.values()}
    return {str(value)}
