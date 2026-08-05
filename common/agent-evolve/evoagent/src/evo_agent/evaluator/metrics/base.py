"""Metric contracts — re-export upstream ``Metric`` and define ``BatchMetric``.

Per-case metrics inherit the upstream ``Metric`` ABC and return a single float
or a ``dict[str, float]`` per case. Batch metrics operate across a *collection*
of cases — accumulating per-case confusion counts and producing a micro
aggregate (F1/ACC/precision/recall) at the end.

Custom metric contract (see ``__init__`` for registration):

    class MyF1(BatchMetric):
        name = "my_f1"
        def reset(self) -> None: ...
        def accumulate(self, prediction, label, **kwargs) -> None: ...
        def aggregate(self) -> dict[str, float]: ...
"""

from __future__ import annotations

from typing import Any, Protocol, runtime_checkable

# Re-export the upstream abstract base so custom per-case metrics can import
# it from a single, EvoAgent-owned location.
from openjiuwen.agent_evolving.evaluator.metrics.base import Metric

__all__ = ["Metric", "BatchMetric", "BatchMetricResult"]


# A batch aggregate result: dimension keys (``precision``/``recall``/``f1``/...)
# mapped to floats in ``[0.0, 1.0]``. The primary selectable score is exposed
# under the ``score`` key by built-in batch metrics.
BatchMetricResult = dict[str, float]


@runtime_checkable
class BatchMetric(Protocol):
    """Compute an aggregate score across a *collection* of cases.

    Unlike per-case ``Metric`` (one score per case), a batch metric accumulates
    per-case signals (e.g. TP/FP/FN) and produces a single micro aggregate at the
    end. Accumulation is **serial** — the aggregator runs it after
    ``batch_evaluate`` completes, so implementations must not rely on
    thread-local state being merged across workers.

    Lifecycle per aggregation run: ``reset`` → ``accumulate`` (once per case) →
    ``aggregate``.
    """

    name: str

    def reset(self) -> None:
        """Clear all accumulated state. Called before each aggregation run."""
        ...

    def accumulate(self, prediction: Any, label: Any, **kwargs: Any) -> None:
        """Fold one case's (prediction, label) into the running state."""
        ...

    def aggregate(self) -> BatchMetricResult:
        """Return the aggregate scores (e.g. ``{precision, recall, f1, score}``)."""
        ...
