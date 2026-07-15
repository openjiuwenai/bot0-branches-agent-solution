"""Evaluator metrics — per-case ``Metric`` implementations, batch collection
metrics, and a runtime registry.

Per-case metrics (inherit upstream ``Metric``, ``compute`` → float in [0,1]):
- ``contains``       — substring containment
- ``keyword_hit``    — 1.0 if ANY expected keyword is present (binary hit/miss)
- ``keyword_recall`` — fraction of expected keywords hit
- ``regex``          — regex match (full or search)
- ``numeric_tolerance`` — numeric match within abs/rel tolerance
- ``exact_match`` / ``normalized_exact_match`` — re-registered upstream

Batch collection metrics (``BatchMetric``, accumulate → micro aggregate):
- ``set_overlap`` — micro F1/ACC/precision/recall over item-set overlap

Register custom metrics at runtime via ``register_metric`` / ``register_batch_metric``;
the factory resolves names against the registry.
"""

from __future__ import annotations

from evo_agent.evaluator.metrics.base import BatchMetric, BatchMetricResult, Metric
from evo_agent.evaluator.metrics.batch import BatchMetricAggregator, SetOverlapBatchMetric
from evo_agent.evaluator.metrics.per_case import (
    ContainsMetric,
    KeywordHitMetric,
    KeywordRecallMetric,
    NumericToleranceMetric,
    RegexMatchMetric,
)
from evo_agent.evaluator.metrics.registry import (
    BatchMetricFactory,
    MetricFactory,
    _register_defaults,
    get_batch_metric,
    get_metric,
    register_batch_metric,
    register_metric,
)

__all__ = [
    "BatchMetric",
    "BatchMetricAggregator",
    "BatchMetricFactory",
    "BatchMetricResult",
    "ContainsMetric",
    "KeywordHitMetric",
    "KeywordRecallMetric",
    "Metric",
    "MetricFactory",
    "NumericToleranceMetric",
    "RegexMatchMetric",
    "SetOverlapBatchMetric",
    "get_batch_metric",
    "get_metric",
    "register_batch_metric",
    "register_metric",
]

# Register built-in metrics on first import of the package.
_register_defaults()
