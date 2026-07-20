"""Identity-preserving outcomes for evaluation batches."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase


@dataclass(frozen=True)
class EvaluationFailure:
    """Artifact-safe diagnostics for one failed case."""

    category: str
    safe_message: str
    invocation_id: str | None = None
    response_sha256: str | None = None
    response_chars: int | None = None


@dataclass(frozen=True)
class EvaluationOutcome:
    """Exactly one success or failure at a stable batch identity."""

    index: int
    case_id: str
    case: Case
    trajectory: Any
    evaluated: EvaluatedCase | None
    failure: EvaluationFailure | None

    def __post_init__(self) -> None:
        if (self.evaluated is None) == (self.failure is None):
            raise ValueError("outcome must contain exactly one of evaluated or failure")


@dataclass(frozen=True)
class EvaluationBatchResult:
    """Ordered, lossless result of evaluating one input batch."""

    outcomes: tuple[EvaluationOutcome, ...]

    @property
    def successes(self) -> tuple[EvaluatedCase, ...]:
        return tuple(
            outcome.evaluated for outcome in self.outcomes if outcome.evaluated is not None
        )

    @property
    def attempted_count(self) -> int:
        return len(self.outcomes)

    @property
    def evaluated_count(self) -> int:
        return len(self.successes)

    @property
    def skipped_count(self) -> int:
        return self.attempted_count - self.evaluated_count

    @property
    def coverage(self) -> float:
        return self.evaluated_count / self.attempted_count if self.attempted_count else 1.0


__all__ = ["EvaluationBatchResult", "EvaluationFailure", "EvaluationOutcome"]
