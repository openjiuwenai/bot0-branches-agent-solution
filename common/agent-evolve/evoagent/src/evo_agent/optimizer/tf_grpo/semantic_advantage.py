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
    return f"""请总结该技能文档变体的一次 rollout，供后续组内相对比较使用。

**变体 id：** {variant_id}
**平均分：** {mean_score:.4f}（共 {len(case_briefs)} 条用例）

**技能变体（SKILL.md）：**
{skill}

**各用例结果：**
{cases_text}

请写一份约 8–12 条要点的简洁摘要，便于后续跨变体对比。
摘要必须包含：
1. 总体结果：平均分；按 case id 列出成功与失败用例
2. 技能侧重：本 SKILL.md 强调的具体规则/章节/示例（不要空泛称赞）
3. 错误模式：期望标签与预测之间的反复偏差；有关键错误字段时请引用
4. 可能的技能缺口：导致失败的具体缺失说明 / 边界情况 / 输出契约问题
5. 高分用例上应保留的优势：看似有效的具体指导

约束：
- 不得编造所给输出之外的 case 事实
- 不要重写或整篇粘贴 SKILL.md
- 只输出摘要正文（不要前言）

Rollout 摘要：
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
            f"**变体 {r.variant_id}**（得分: {r.score:.4f}）：\n"
            f"{r.summary or '（无摘要）'}"
        )
    summaries_text = "\n\n".join(parts)
    return f"""请分析多次技能优化尝试，提炼关键洞察。

{existing if existing else "（经验库为空。）"}

**当前 Rollout 组：**
{summaries_text}

请提炼 2–3 条关于「何种变体更成功」的关键洞察，重点关注：
- 与更高分数相关的模式
- 低分变体中的常见错误
- 需要**补充**的具体缺失要素
- 对后续优化可执行的指导

以简洁要点形式输出，并给出具体建议。

关键洞察：
"""


def build_library_update_prompt(
    semantic_advantage: str,
    experience_library: ExperienceLibrary,
) -> str:
    current = "\n".join(
        f"{i + 1}. {e.content}" for i, e in enumerate(experience_library.experiences)
    )
    return f"""请管理技能优化用的经验库。

**当前经验库：**
{current if current else "（空）"}

**新洞察：**
{semantic_advantage}

决定如何更新经验库。聚焦**可执行、具体**的指导。

操作类型（operation 字段保持英文）：
- Add：新增带具体建议的经验
- Delete：删除空泛或无用的经验（0-based 下标）
- Modify：把某条经验改得更具体（0-based 下标）
- Keep：不做变更

仅输出 JSON 列表：
```json
[
  {{"operation": "Add", "content": "补充带输入/输出的具体用法示例"}},
  {{"operation": "Modify", "index": 0, "content": "补充非法输入等边界情况说明"}},
  {{"operation": "Delete", "index": 1}},
  {{"operation": "Keep"}}
]
```

操作：
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
        f"变体 {variant_id} 在 {n_cases} 条评估用例上的平均分为 {score:.4f}。"
    )
