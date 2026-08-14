"""Agent-as-judge schemas — per-dimension verdict + LLM aggregator output.

These pydantic models are the contract between three actors:

- the judge agent subprocess (emits ``DimensionJudgment`` constrained by the
  JSON schema fed to ``claude --json-schema`` / ``codex --output-schema``);
- the LLM aggregator (emits ``AggregatorOutput`` parsed via
  :func:`parse_structured_output` + :func:`aggregator_output_validator`);
- the ``AgentEvaluator`` (stamps ``dimension`` from the requested dimension
  name, builds the ``EvaluatedCase.reason`` JSON blob).

Score fields are plain ``float`` (no pydantic range constraint): callers clamp
to ``[0, 1]`` at consumption time so a stray ``1.05`` does not hard-fail a run.
"""

from __future__ import annotations

import math
from typing import Any, Literal

from pydantic import BaseModel, Field

from evo_agent.llm.structured_output import ValidationResult

__all__ = [
    "AggregatorOutput",
    "DimensionJudgment",
    "SkillAttribution",
    "aggregator_output_validator",
    "dimension_judgment_json_schema",
]


class DimensionJudgment(BaseModel):
    """One per-dimension agent verdict: ``{dimension, score, reasoning}``.

    ``dimension`` is overwritten by the orchestrator with the *requested*
    dimension name (the agent may parrot the wrong label), so the field here
    is only the parser's best-effort echo.
    """

    dimension: str
    score: float
    reasoning: str = ""


class SkillAttribution(BaseModel):
    """One skill attribution item, spec-aligned.

    Shape matches ``evaluator/docs/superpowers/specs/
    2026-06-15-metric-evaluator-attribution-api-design.md``: each item carries
    ``skill_name``, ``usage_status``, ``impact``, ``reason``.
    """

    skill_name: str
    usage_status: Literal["executed", "not_executed", "misused", "unknown"] = "unknown"
    impact: Literal["positive", "negative", "neutral", "none"] = "none"
    reason: str = ""


class AggregatorOutput(BaseModel):
    """Aggregator output: overall score + plural skill attribution.

    ``overall_score`` is produced by a deterministic :class:`WeightScorer`
    (evaluator-side script), not by the LLM — the LLM only emits the attribution
    fields. ``attribution_status="failed"`` means the aggregator LLM
    self-reported inability (score preserved, bounded ``attribution_error``);
    it does **not** mean an unknown skill — unknown skills raise
    ``EvaluationError`` (fail-fast) in :class:`SkillAggregator` before this model
    is returned.
    """

    overall_score: float = 0.0
    skill_attributions: list[SkillAttribution] = Field(default_factory=list)
    attribution_status: Literal["completed", "failed"]
    attribution_error: str | None = None


def dimension_judgment_json_schema() -> dict[str, Any]:
    """JSON Schema constraining the per-dimension agent's final output.

    Written to the isolated workdir and fed to ``claude --json-schema`` /
    ``codex --output-schema`` so the agent's final message is a structured
    object, not free text.
    """

    return {
        "type": "object",
        "properties": {
            "dimension": {"type": "string"},
            "score": {"type": "number", "minimum": 0, "maximum": 1},
            "reasoning": {"type": "string"},
        },
        "required": ["dimension", "score", "reasoning"],
        "additionalProperties": False,
    }


def aggregator_output_validator(data: dict[str, Any]) -> ValidationResult:
    """Business-validation hook for :func:`parse_structured_output`.

    Checks the shape the aggregator's *attribution* output must satisfy before
    pydantic strict validation: ``attribution_status`` is a recognized enum, and
    ``skill_attributions`` is a list of dicts each carrying a string
    ``skill_name``. ``overall_score`` is no longer required — it is computed by a
    deterministic :class:`WeightScorer` (evaluator-side), not emitted by the LLM;
    if the LLM nonetheless includes it, it must be a finite number. Returning
    ``ok=False`` drives the invocation's retry path (mirrors
    ``_validate_evaluator_output`` in ``evaluators/llm.py``).
    """

    raw_score = data.get("overall_score")
    if raw_score is not None:
        if isinstance(raw_score, bool) or not isinstance(raw_score, (int, float)):
            return ValidationResult(False, "field_type", "overall_score must be a number")
        if not math.isfinite(raw_score):
            return ValidationResult(False, "field_type", "overall_score must be finite")
    raw_status = data.get("attribution_status")
    if not isinstance(raw_status, str) or raw_status not in {"completed", "failed"}:
        return ValidationResult(
            False, "field_type", "attribution_status must be 'completed' or 'failed'"
        )
    raw_attributions = data.get("skill_attributions", [])
    if not isinstance(raw_attributions, list):
        return ValidationResult(False, "field_type", "skill_attributions must be a list")
    for item in raw_attributions:
        if not isinstance(item, dict) or not isinstance(item.get("skill_name"), str):
            return ValidationResult(
                False, "field_type", "each skill_attribution needs a string skill_name"
            )
    return ValidationResult(True)
