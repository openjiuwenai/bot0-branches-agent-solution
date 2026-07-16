"""Semantic advantage extraction and experience-library ops parsing."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from typing import Any

from evo_agent.optimizer.tf_grpo.experience_library import (
    ExperienceLibrary,
    LibraryOperation,
)

_JSON_FENCE_RE = re.compile(r"```(?:json)?\s*([\s\S]*?)```", re.IGNORECASE)


@dataclass(frozen=True)
class RolloutSummary:
    """One group member after scoring."""

    variant_id: str
    content: str
    score: float
    summary: str = ""


@dataclass(frozen=True)
class CaseOutcomeBrief:
    """Compact per-case outcome fed into LLM rollout summarization."""

    case_id: str
    score: float
    expected: str
    prediction: str
    reason: str = ""


def scores_have_variance(rollouts: list[RolloutSummary], *, atol: float = 1e-9) -> bool:
    if len(rollouts) < 2:
        return False
    scores = [r.score for r in rollouts]
    return max(scores) - min(scores) > atol


def _clip(text: str, max_chars: int) -> str:
    raw = text or ""
    if max_chars <= 0 or len(raw) <= max_chars:
        return raw
    return raw[: max_chars - 20] + "\n...[truncated]..."


def _prediction_text(answer: Any) -> str:
    if answer is None:
        return ""
    if isinstance(answer, dict):
        if "answer" in answer and answer["answer"] is not None:
            value = answer["answer"]
            if isinstance(value, (dict, list)):
                return json.dumps(value, ensure_ascii=False)
            return str(value)
        return json.dumps(answer, ensure_ascii=False)
    return str(answer)


def _expected_text(case: Any) -> str:
    label = getattr(case, "label", None)
    if isinstance(label, dict):
        for key in ("expected_result", "expected_behavior", "label"):
            if key in label and label[key] is not None:
                value = label[key]
                if isinstance(value, (dict, list)):
                    return json.dumps(value, ensure_ascii=False)
                return str(value)
        return json.dumps(label, ensure_ascii=False)
    if label is None:
        return ""
    return str(label)


def case_outcome_briefs_from_evaluated(
    evaluated: list[Any],
    *,
    answer_max_chars: int = 1200,
) -> list[CaseOutcomeBrief]:
    """Build prompt-ready briefs from EvaluatedCase-like objects."""
    briefs: list[CaseOutcomeBrief] = []
    for ec in evaluated:
        case = getattr(ec, "case", None)
        case_id = str(getattr(ec, "case_id", None) or getattr(case, "case_id", "") or "?")
        briefs.append(
            CaseOutcomeBrief(
                case_id=case_id,
                score=float(getattr(ec, "score", 0.0) or 0.0),
                expected=_clip(_expected_text(case), 400),
                prediction=_clip(_prediction_text(getattr(ec, "answer", None)), answer_max_chars),
                reason=_clip(str(getattr(ec, "reason", "") or ""), 300),
            )
        )
    return briefs


def build_rollout_summary_prompt(
    *,
    variant_id: str,
    skill_content: str,
    case_briefs: list[CaseOutcomeBrief],
    mean_score: float,
    max_skill_chars: int = 20000,
) -> str:
    """Prompt for one-variant summary used later by semantic-advantage extraction."""
    skill = _clip(skill_content or "", max_skill_chars)
    case_blocks: list[str] = []
    for brief in case_briefs:
        reason_line = f"\n- Reason: {brief.reason}" if brief.reason.strip() else ""
        case_blocks.append(
            f"**Case {brief.case_id}** (score={brief.score:.4f})\n"
            f"- Expected: {brief.expected or '(none)'}\n"
            f"- Prediction/output: {brief.prediction or '(empty)'}{reason_line}"
        )
    cases_text = "\n\n".join(case_blocks) if case_blocks else "(No scored cases.)"
    return f"""Summarize one skill-document variant's rollout for later group-relative comparison.

**Variant id:** {variant_id}
**Mean score:** {mean_score:.4f} over {len(case_briefs)} case(s)

**Skill variant (SKILL.md):**
{skill}

**Per-case results:**
{cases_text}

Write a concise summary (about 8–12 bullets) that a later step can compare across variants.
The summary MUST include:
1. Overall outcome: mean score; which cases succeeded vs failed (by case id)
2. Skill emphasis: concrete rules/sections/examples this SKILL.md stresses (not generic praise)
3. Error modes: recurring mismatches between expected label and prediction; quote key wrong fields when present
4. Likely skill gaps that caused failures (specific missing instructions / edge cases / output-contract issues)
5. Strengths to keep on high-scoring cases (specific guidance that seemed to help)

Constraints:
- Do not invent case facts beyond the provided outputs
- Do not rewrite or dump the full SKILL.md
- Output summary text only (no preamble)

Rollout summary:
"""


def build_semantic_advantage_prompt(
    rollouts: list[RolloutSummary],
    experience_library: ExperienceLibrary,
    *,
    domain: str = "markdown",
) -> str:
    existing = experience_library.to_prompt_context(domain)
    parts = []
    for r in rollouts:
        parts.append(
            f"**Variant {r.variant_id}** (Score: {r.score:.4f}):\n"
            f"{r.summary or '(no summary)'}"
        )
    summaries_text = "\n\n".join(parts)
    return f"""Analyze multiple skill optimization attempts to extract key insights.

{existing if existing else "(Experience library is empty.)"}

**Current Rollout Group:**
{summaries_text}

Extract 2-3 key insights about what makes a skill variant successful. Focus on:
- Patterns that correlate with higher scores
- Common mistakes in lower-scoring variants
- SPECIFIC missing elements that need to be ADDED
- Actionable guidance for future optimization

Format as concise bullet points with specific recommendations.

Key Insights:
"""


def build_library_update_prompt(
    semantic_advantage: str,
    experience_library: ExperienceLibrary,
) -> str:
    current = "\n".join(
        f"{i + 1}. {e.content}" for i, e in enumerate(experience_library.experiences)
    )
    return f"""Manage an experience library for skill optimization.

**Current Experience Library:**
{current if current else "(Empty)"}

**New Insights:**
{semantic_advantage}

Decide how to update the library. Focus on ACTIONABLE, SPECIFIC guidance.

Operations:
- Add: Add new experience with CONCRETE recommendations
- Delete: Remove vague or unhelpful experience (0-based index)
- Modify: Make experience more specific (0-based index)
- Keep: No changes

Provide operations as a JSON list only:
```json
[
  {{"operation": "Add", "content": "Add concrete usage examples with input/output"}},
  {{"operation": "Modify", "index": 0, "content": "Document edge cases for invalid inputs"}},
  {{"operation": "Delete", "index": 1}},
  {{"operation": "Keep"}}
]
```

Operations:
"""


def parse_library_operations(raw: str) -> list[LibraryOperation]:
    """Parse LLM ops JSON; tolerate fences and non-list wrappers."""
    text = (raw or "").strip()
    if not text:
        return [LibraryOperation(operation="Keep")]

    fence = _JSON_FENCE_RE.search(text)
    if fence:
        text = fence.group(1).strip()

    try:
        data: Any = json.loads(text)
    except json.JSONDecodeError:
        # Try to find a JSON array substring
        start = text.find("[")
        end = text.rfind("]")
        if start < 0 or end <= start:
            return [LibraryOperation(operation="Keep")]
        try:
            data = json.loads(text[start : end + 1])
        except json.JSONDecodeError:
            return [LibraryOperation(operation="Keep")]

    if isinstance(data, dict) and "operations" in data:
        data = data["operations"]
    if not isinstance(data, list):
        return [LibraryOperation(operation="Keep")]

    ops: list[LibraryOperation] = []
    for item in data:
        if not isinstance(item, dict):
            continue
        op_name = str(item.get("operation", "Keep")).strip()
        if op_name not in {"Add", "Delete", "Modify", "Keep"}:
            continue
        index = item.get("index")
        ops.append(
            LibraryOperation(
                operation=op_name,
                content=item.get("content"),
                index=int(index) if index is not None else None,
            )
        )
    return ops or [LibraryOperation(operation="Keep")]


def fallback_rollout_summary(variant_id: str, score: float, n_cases: int) -> str:
    return (
        f"Variant {variant_id} scored {score:.4f} "
        f"averaged over {n_cases} evaluation case(s)."
    )
