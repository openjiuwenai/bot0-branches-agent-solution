"""Agent-as-judge schemas — per-dimension verdict + attribution-agent output.

These pydantic models are the contract between three actors:

- the judge agent subprocess (emits ``DimensionJudgment`` constrained by the
  JSON schema fed to ``claude --json-schema`` / ``codex --output-schema``);
- the attribution agent subprocess (emits ``skill_attributions``, parsed via
  :func:`aggregator_output_validator`);
- the ``AgentEvaluator`` (stamps ``dimension`` from the requested dimension
  name, computes ``overall_score`` evaluator-side via ``WeightScorer``,
  builds the ``EvaluatedCase.reason`` JSON blob).

``overall_score`` is produced by the evaluator-side ``WeightScorer``
(deterministic, reproducible). The attribution agent uses the
``attribution_calculator`` skill script for per-dimension threshold checking
(not for the primary score) when ``dimension_thresholds`` are provided.

Score fields are plain ``float`` (no pydantic range constraint): callers clamp
to ``[0, 1]`` at consumption time so a stray ``1.05`` does not hard-fail a run.
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field

from evo_agent.llm.structured_output import ValidationResult

__all__ = [
    "AggregatorOutput",
    "DimensionJudgment",
    "SkillAttribution",
    "aggregator_output_validator",
    "attribution_output_json_schema",
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
    """Attribution-agent output + evaluator-side overall score.

    ``overall_score`` is produced by the evaluator-side ``WeightScorer``
    (deterministic, reproducible) — not by the attribution agent. The agent
    emits ``skill_attributions`` and ``attribution_status``; the evaluator
    fills ``overall_score`` after parsing.
    ``attribution_status="failed"`` means the attribution agent self-reported
    inability (score preserved, bounded ``attribution_error``); it does
    **not** mean an unknown skill — unknown skills raise ``EvaluationError``
    (fail-fast) in :func:`parse_attribution_output` before this model is
    returned.
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


def attribution_output_json_schema() -> dict[str, Any]:
    """JSON Schema constraining the attribution agent's final output.

    Parallel to :func:`dimension_judgment_json_schema`; written to the workdir
    and fed to the judge runtime (``claude --json-schema`` /
    ``codex --output-schema``) so the final attribution agent — the subprocess
    that fuses all per-dimension verdicts into plural skill attribution — emits a
    structured object. ``overall_score`` and ``dimension_weights`` are no
    longer emitted by the agent (evaluator-side ``WeightScorer`` handles the
    primary score).
    """

    return {
        "type": "object",
        "properties": {
            "skill_attributions": {
                "type": "array",
                "items": {
                    "type": "object",
                    "properties": {
                        "skill_name": {"type": "string"},
                        "usage_status": {
                            "type": "string",
                            "enum": ["executed", "not_executed", "misused", "unknown"],
                        },
                        "impact": {
                            "type": "string",
                            "enum": ["positive", "negative", "neutral", "none"],
                        },
                        "reason": {"type": "string"},
                    },
                    "required": ["skill_name", "usage_status", "impact", "reason"],
                    "additionalProperties": False,
                },
            },
            "attribution_status": {"type": "string", "enum": ["completed", "failed"]},
            "attribution_error": {"type": ["string", "null"]},
        },
        "required": [
            "skill_attributions",
            "attribution_status",
        ],
        "additionalProperties": False,
    }


def aggregator_output_validator(data: dict[str, Any]) -> ValidationResult:
    """Business-validation hook for the attribution agent's output.

    Checks the shape the attribution agent's output must satisfy before pydantic
    strict validation: ``attribution_status`` is a recognized enum, and
    ``skill_attributions`` is a list of dicts each carrying a string
    ``skill_name``. Returning ``ok=False`` makes
    :func:`parse_attribution_output` raise an ``EvaluationError`` (the
    subprocess path is single-shot, no retry).
    """

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
