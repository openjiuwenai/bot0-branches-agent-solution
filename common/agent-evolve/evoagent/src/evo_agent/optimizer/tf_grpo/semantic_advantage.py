"""Semantic advantage extraction and experience-library ops parsing."""

from __future__ import annotations

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from evo_agent.optimizer.tf_grpo.experience_library import (
    ExperienceLibrary,
    LibraryOperation,
)
from evo_agent.optimizer.tf_grpo.prompts import (
    LIBRARY_UPDATE,
    ROLLOUT_SUMMARY,
    SEMANTIC_ADVANTAGE,
    SEMANTIC_ADVANTAGE_NOTE_NO_VARIANCE,
    SEMANTIC_ADVANTAGE_NOTE_WITH_VARIANCE,
    load_tf_grpo_prompt,
    render_prompt,
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
    return raw[: max_chars - 20] + "\n...[已截断]..."


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
    scenario_name: str | None = None,
    scenarios_dir: Path | str | None = None,
) -> str:
    """为后续组内语义优势抽取生成单变体 rollout 摘要提示词。"""
    skill = _clip(skill_content or "", max_skill_chars)
    case_blocks: list[str] = []
    for brief in case_briefs:
        reason_line = f"\n- 评分说明: {brief.reason}" if brief.reason.strip() else ""
        case_blocks.append(
            f"**用例 {brief.case_id}**（得分={brief.score:.4f}）\n"
            f"- 期望: {brief.expected or '（无）'}\n"
            f"- 预测/输出: {brief.prediction or '（空）'}{reason_line}"
        )
    cases_text = "\n\n".join(case_blocks) if case_blocks else "（无已评分用例。）"
    load_kw = {"scenario_name": scenario_name, "scenarios_dir": scenarios_dir}
    return render_prompt(
        load_tf_grpo_prompt(ROLLOUT_SUMMARY, **load_kw),
        {
            "variant_id": variant_id,
            "mean_score": f"{mean_score:.4f}",
            "n_cases": len(case_briefs),
            "skill_content": skill,
            "cases_text": cases_text,
        },
    )


def build_semantic_advantage_prompt(
    rollouts: list[RolloutSummary],
    experience_library: ExperienceLibrary,
    *,
    domain: str = "markdown",
    has_score_variance: bool = True,
    scenario_name: str | None = None,
    scenarios_dir: Path | str | None = None,
) -> str:
    existing = experience_library.to_prompt_context(domain)
    parts = []
    for r in rollouts:
        parts.append(
            f"**变体 {r.variant_id}**（得分: {r.score:.4f}）：\n"
            f"{r.summary or '（无摘要）'}"
        )
    summaries_text = "\n\n".join(parts)
    load_kw = {"scenario_name": scenario_name, "scenarios_dir": scenarios_dir}
    note_name = (
        SEMANTIC_ADVANTAGE_NOTE_WITH_VARIANCE
        if has_score_variance
        else SEMANTIC_ADVANTAGE_NOTE_NO_VARIANCE
    )
    variance_note = load_tf_grpo_prompt(note_name, **load_kw)
    return render_prompt(
        load_tf_grpo_prompt(SEMANTIC_ADVANTAGE, **load_kw),
        {
            "experience_context": existing if existing else "（经验库为空。）",
            "summaries_text": summaries_text,
            "variance_note": variance_note,
        },
    )


def build_library_update_prompt(
    semantic_advantage: str,
    experience_library: ExperienceLibrary,
    *,
    scenario_name: str | None = None,
    scenarios_dir: Path | str | None = None,
) -> str:
    current = "\n".join(
        f"{i + 1}. {e.content}" for i, e in enumerate(experience_library.experiences)
    )
    load_kw = {"scenario_name": scenario_name, "scenarios_dir": scenarios_dir}
    return render_prompt(
        load_tf_grpo_prompt(LIBRARY_UPDATE, **load_kw),
        {
            "current_library": current if current else "（空）",
            "semantic_advantage": semantic_advantage,
        },
    )


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
        f"变体 {variant_id} 在 {n_cases} 条评估用例上的平均分为 {score:.4f}。"
    )
