"""Scoring primitives — EvaluationScores dict and EvaluationError exception."""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any


class EvaluationError(Exception):
    """Raised when an evaluation infrastructure failure prevents scoring.

    Distinguishes LLM call failures (timeout, rate-limit, model output error)
    and JSON parse failures from legitimate low-quality scores, so that
    infrastructure problems do not pollute the skill optimization signal.
    """

    def __init__(
        self,
        message: str | None = None,
        *,
        category: str = "schema_validation_error",
        safe_message: str | None = None,
        invocation_id: str | None = None,
        response_sha256: str | None = None,
        response_chars: int | None = None,
        raw_response: str | None = None,
        invocation_diagnostics: Mapping[str, Any] | None = None,
    ) -> None:
        safe = safe_message or message or "evaluation failed"
        super().__init__(safe)
        self.category = category
        self.safe_message = safe
        self.invocation_id = invocation_id
        self.response_sha256 = response_sha256
        self.response_chars = response_chars
        self.raw_response = raw_response
        self.invocation_diagnostics = dict(invocation_diagnostics or {})
        self.logged = False


class EvaluationScores(dict[str, float]):
    """Score dictionary carrying evaluation diagnostics.

    The dict keys are dimension names (task_completion, trajectory_quality, safety)
    when available.  The essential fields are ``is_pass``, ``score``, and
    ``attributed_skill`` which come directly from the LLM output.
    """

    def __init__(
        self,
        scores: dict[str, float],
        *,
        reason: str = "",
        is_pass: bool = True,
        score: float = 0.0,
        attributed_skill: str = "",
        repaired: bool = False,
        parse_mode: str = "exact",
        repair_operations: tuple[dict[str, Any], ...] = (),
        repaired_response: str | None = None,
    ) -> None:
        super().__init__(scores)
        self.reason = reason
        self.is_pass = is_pass
        self.score = score
        self.attributed_skill = attributed_skill
        self.repaired = repaired
        self.parse_mode = parse_mode
        self.repair_operations = repair_operations
        self.repaired_response = repaired_response
