"""Scoring primitives — EvaluationScores dict and EvaluationError exception."""

from __future__ import annotations


class EvaluationError(Exception):
    """Raised when an evaluation infrastructure failure prevents scoring.

    Distinguishes LLM call failures (timeout, rate-limit, model output error)
    and JSON parse failures from legitimate low-quality scores, so that
    infrastructure problems do not pollute the skill optimization signal.
    """


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
    ) -> None:
        super().__init__(scores)
        self.reason = reason
        self.is_pass = is_pass
        self.score = score
        self.attributed_skill = attributed_skill
