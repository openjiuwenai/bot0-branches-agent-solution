"""Weight-scorer registry — deterministic final-score scripts for the aggregator.

The agent-as-judge aggregator used to ask the LLM for ``overall_score`` and
trust it. That made the final score non-reproducible and able to drift from the
preset weights. This module replaces that with a family of **deterministic,
evaluator-side scripts** (requirement: a weight-calculation script returns the
final score; the script is part of a skill and is **called by the evaluator**,
not by the judge subprocess — so the Agent stays read-only under Q1).

A :class:`WeightScorer` is a stateless callable: given the per-dimension
judgments and the preset weights, it returns a single ``[0, 1]`` float. Scorers
register exactly like dimensions/presets (module-level ``_REGISTRY`` +
``register_scorer`` / ``get_scorer`` / ``list_scorers`` + an idempotent
``_register_default_scorers``); a preset names one via ``JudgePreset.scorer``.

Default scorers:

- ``weighted_sum`` — normalized weighted average of all present dimensions.
- ``task_completion_gated`` (default) — ``overall = tc × (weighted avg of the
  other dimensions)``. Task completion is a multiplicative gate: if the task was
  not done, the overall trends to zero regardless of how clean the rest of the
  trajectory is. When ``task_completion`` is absent from the judgments, this
  scorer falls back to ``weighted_sum`` (so custom presets without the gate
  dimension still work).
"""

from __future__ import annotations

import logging
from typing import Protocol, runtime_checkable

from evo_agent.evaluator.agent_judge.schemas import DimensionJudgment

logger = logging.getLogger(__name__)

__all__ = [
    "TaskCompletionGatedScorer",
    "WeightScorer",
    "WeightedSumScorer",
    "get_scorer",
    "list_scorers",
    "register_scorer",
]

_GATE_DIMENSION = "task_completion"


@runtime_checkable
class WeightScorer(Protocol):
    """Deterministic final-score script run by the evaluator (not the Agent)."""

    def score(self, judgments: list[DimensionJudgment], weights: dict[str, float]) -> float:
        """Return a single ``[0, 1]`` overall score from per-dim judgments."""
        ...


def _clamp(value: float) -> float:
    return max(0.0, min(1.0, value))


def _judgment_map(judgments: list[DimensionJudgment]) -> dict[str, float]:
    """Collapse judgments to ``{dimension: score}`` (last wins on duplicate dims)."""
    return {j.dimension: float(j.score) for j in judgments}


class WeightedSumScorer:
    """Normalized weighted average of all present dimensions.

    ``overall = Σ(w_d · score_d) / Σ w_d`` over the dimensions present in the
    judgments, using ``weights.get(d, 0.0)``. If no present dimension has a
    positive weight, falls back to the simple mean (equal weights). Empty
    judgments → ``0.0``.
    """

    def score(self, judgments: list[DimensionJudgment], weights: dict[str, float]) -> float:  # pylint: disable=no-self-use
        scores = _judgment_map(judgments)
        if not scores:
            return 0.0
        num = 0.0
        den = 0.0
        for dim, score in scores.items():
            w = float(weights.get(dim, 0.0))
            num += w * score
            den += w
        if den <= 0.0:
            # No positive weight for any present dimension → equal-weight mean.
            return _clamp(sum(scores.values()) / len(scores))
        return _clamp(num / den)


class TaskCompletionGatedScorer:
    """Default scorer: ``overall = tc × (weighted avg of the other dimensions)``.

    Task completion is a multiplicative gate on the weighted quality of the
    remaining dimensions; the gate dimension's own weight is not used (it acts
    as the multiplier). Weights of the other dimensions are renormalized over
    those dimensions. Falls back to :class:`WeightedSumScorer` when the gate
    dimension is absent, and to the gate value alone when no other dimension
    is present.
    """

    def __init__(self, gate_dimension: str = _GATE_DIMENSION) -> None:
        self._gate = gate_dimension
        self._fallback = WeightedSumScorer()

    def score(self, judgments: list[DimensionJudgment], weights: dict[str, float]) -> float:
        scores = _judgment_map(judgments)
        if self._gate not in scores:
            return self._fallback.score(judgments, weights)
        gate_score = scores[self._gate]
        other = {dim: score for dim, score in scores.items() if dim != self._gate}
        if not other:
            return _clamp(gate_score)
        num = 0.0
        den = 0.0
        for dim, score in other.items():
            w = float(weights.get(dim, 0.0))
            num += w * score
            den += w
        if den <= 0.0:
            other_avg = sum(other.values()) / len(other)
        else:
            other_avg = num / den
        return _clamp(gate_score * other_avg)


_REGISTRY: dict[str, WeightScorer] = {}


def register_scorer(name: str, scorer: WeightScorer) -> None:
    """Register a scorer under ``name`` (overwrites + WARNING)."""
    if name in _REGISTRY and _REGISTRY[name] is not scorer:
        logger.warning("Overwriting registered judge scorer %r", name)
    _REGISTRY[name] = scorer


def get_scorer(name: str) -> WeightScorer:
    """Look up a registered scorer. Raises ``ValueError`` if unknown."""
    try:
        return _REGISTRY[name]
    except KeyError as exc:
        raise ValueError(
            f"Unknown judge scorer: {name!r}. Registered scorers: {sorted(_REGISTRY)!r}"
        ) from exc


def list_scorers() -> list[str]:
    """Return the sorted names of all registered scorers."""
    return sorted(_REGISTRY)


def _register_default_scorers() -> None:
    """Register the built-in scorers (idempotent)."""
    register_scorer("weighted_sum", WeightedSumScorer())
    register_scorer("task_completion_gated", TaskCompletionGatedScorer())


_register_default_scorers()
