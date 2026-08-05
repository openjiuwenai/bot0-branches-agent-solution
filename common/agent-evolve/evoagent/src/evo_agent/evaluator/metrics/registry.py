"""Runtime metric registry — register/lookup metrics by name.

Registrants are **zero-arg factories** (a class with a no-arg constructor, or a
lambda). Storing factories lets built-ins pin constructor args (e.g.
``exact_match`` must use ``normalize=False``) while custom classes register
directly:

    from evo_agent.evaluator.metrics import register_metric, Metric

    class MyMetric(Metric):
        @property
        def name(self) -> str: return "my_metric"
        def compute(self, prediction, label, **kwargs) -> float: ...

    register_metric("my_metric", MyMetric)   # class used as zero-arg factory

The factory (``evo_agent.evaluator.factory``) resolves metric names against
this registry: ``get_metric(name)()``. Re-registering a name overwrites the
previous factory with a WARNING.
"""

from __future__ import annotations

import logging
from collections.abc import Callable

from evo_agent.evaluator.metrics.base import BatchMetric, Metric

logger = logging.getLogger(__name__)

__all__ = [
    "register_metric",
    "register_batch_metric",
    "get_metric",
    "get_batch_metric",
    "MetricFactory",
    "BatchMetricFactory",
]

# A zero-arg callable returning a fresh metric instance.
MetricFactory = Callable[[], Metric]
BatchMetricFactory = Callable[[], BatchMetric]

_METRIC_REGISTRY: dict[str, MetricFactory] = {}
_BATCH_REGISTRY: dict[str, BatchMetricFactory] = {}


def register_metric(name: str, factory: MetricFactory) -> None:
    """Register a per-case metric factory under ``name`` (overwrites + WARNING)."""
    if name in _METRIC_REGISTRY and _METRIC_REGISTRY[name] is not factory:
        logger.warning("Overwriting registered metric %r", name)
    _METRIC_REGISTRY[name] = factory


def register_batch_metric(name: str, factory: BatchMetricFactory) -> None:
    """Register a batch metric factory under ``name`` (overwrites + WARNING)."""
    if name in _BATCH_REGISTRY and _BATCH_REGISTRY[name] is not factory:
        logger.warning("Overwriting registered batch metric %r", name)
    _BATCH_REGISTRY[name] = factory


def get_metric(name: str) -> MetricFactory:
    """Look up a registered per-case metric factory. Raises ``ValueError`` if unknown."""
    try:
        return _METRIC_REGISTRY[name]
    except KeyError as exc:
        raise ValueError(
            f"Unknown metric: {name!r}. Registered metrics: {sorted(_METRIC_REGISTRY)!r}"
        ) from exc


def get_batch_metric(name: str) -> BatchMetricFactory:
    """Look up a registered batch metric factory. Raises ``ValueError`` if unknown."""
    try:
        return _BATCH_REGISTRY[name]
    except KeyError as exc:
        raise ValueError(
            f"Unknown batch metric: {name!r}. Registered batch metrics: {sorted(_BATCH_REGISTRY)!r}"
        ) from exc


def _register_defaults() -> None:
    """Register the built-in per-case and batch metrics (idempotent)."""
    from openjiuwen.agent_evolving.evaluator.metrics.exact_match import ExactMatchMetric

    from evo_agent.evaluator.metrics.batch import SetOverlapBatchMetric
    from evo_agent.evaluator.metrics.per_case import (
        ContainsMetric,
        KeywordHitMetric,
        KeywordRecallMetric,
        LLMJudgeMetric,
        NumericToleranceMetric,
        RegexMatchMetric,
    )

    # exact_match is case-sensitive (normalize=False); normalized_* normalizes.
    register_metric("exact_match", lambda: ExactMatchMetric(normalize=False))
    register_metric("normalized_exact_match", lambda: ExactMatchMetric(normalize=True))
    register_metric("contains", ContainsMetric)
    register_metric("keyword_hit", KeywordHitMetric)
    register_metric("keyword_recall", KeywordRecallMetric)
    register_metric("regex", RegexMatchMetric)
    register_metric("numeric_tolerance", NumericToleranceMetric)
    register_metric("llm_judge", LLMJudgeMetric)

    register_batch_metric("set_overlap", SetOverlapBatchMetric)
