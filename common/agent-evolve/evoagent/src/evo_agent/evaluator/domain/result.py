"""Evaluation result — the domain-level output of any evaluator."""

from __future__ import annotations

import json
from typing import Any

from pydantic import BaseModel, Field, ValidationError

from evo_agent.evaluator.filters.models import EvaluationStatus, FilterMatch


class EvaluationResult(BaseModel):
    """Domain-level evaluation result, independent of openjiuwen types.

    Attributes:
        status: Whether the result was evaluated normally or filtered.
        score: Composite score in range [0, 1] — provided directly by the LLM.
        is_pass: Whether the trajectory passes evaluation — from LLM output.
        per_metric: Per-dimension scores when available.
        reason: Free-text reasoning or structured JSON.
        attributed_skill: Skill name attributed as the failure root cause.
        filter_matches: Deterministic filter matches that classified the case.
    """

    status: EvaluationStatus = "evaluated"
    score: float = 0.0
    is_pass: bool = True
    per_metric: dict[str, float] | None = None
    reason: str = ""
    attributed_skill: str = ""
    repaired: bool = False
    parse_mode: str = "exact"
    repair_operations: list[dict[str, Any]] = Field(default_factory=list)
    filter_matches: list[FilterMatch] = Field(default_factory=list)

    @classmethod
    def from_evaluated_case(cls, evaluated: Any) -> EvaluationResult:
        """Build from an openjiuwen ``EvaluatedCase``."""
        per_metric: dict[str, float] | None = (
            dict(evaluated.per_metric) if evaluated.per_metric else None
        )

        status: EvaluationStatus = "evaluated"
        attributed_skill: str = ""
        repaired = False
        parse_mode = "exact"
        repair_operations: list[dict[str, Any]] = []
        is_pass: bool = True
        filter_matches: list[FilterMatch] = []
        if evaluated.reason:
            try:
                parsed = json.loads(evaluated.reason)
                if isinstance(parsed, dict):
                    raw_attr = parsed.get("attributed_skill")
                    if isinstance(raw_attr, str):
                        attributed_skill = raw_attr
                    raw_pass = parsed.get("is_pass")
                    if isinstance(raw_pass, bool):
                        is_pass = raw_pass
                    raw_repaired = parsed.get("repaired")
                    if isinstance(raw_repaired, bool):
                        repaired = raw_repaired
                    raw_parse_mode = parsed.get("parse_mode")
                    if isinstance(raw_parse_mode, str):
                        parse_mode = raw_parse_mode
                    raw_operations = parsed.get("repair_operations")
                    if isinstance(raw_operations, list) and all(
                        isinstance(item, dict) for item in raw_operations
                    ):
                        repair_operations = [dict(item) for item in raw_operations]
                    if parsed.get("status") == "filtered":
                        raw_matches = parsed.get("filter_matches", [])
                        filter_matches = [FilterMatch.model_validate(item) for item in raw_matches]
                        status = "filtered"
            except (json.JSONDecodeError, TypeError, AttributeError, ValidationError):
                status = "evaluated"
                filter_matches = []

        return cls(
            status=status,
            score=evaluated.score,
            is_pass=is_pass,
            per_metric=per_metric,
            reason=evaluated.reason,
            attributed_skill=attributed_skill,
            repaired=repaired,
            parse_mode=parse_mode,
            repair_operations=repair_operations,
            filter_matches=filter_matches,
        )
