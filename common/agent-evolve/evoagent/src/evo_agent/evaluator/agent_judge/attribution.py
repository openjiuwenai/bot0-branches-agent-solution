"""Attribution agent — fuse per-dimension verdicts into plural skill attribution.

This module is the **spawn** half of agent-as-judge's attribution step. After the
per-dimension subprocess runs each emit a :class:`DimensionJudgment`, the
:class:`AgentEvaluator` spawns one more bounded coding-agent subprocess (the
"attribution agent") that reads all the verdicts plus the candidate
``skill_names`` and their docs and emits the **plural skill attribution**.

``overall_score`` is produced evaluator-side by :class:`WeightScorer`
(deterministic, reproducible). When ``dimension_thresholds`` are provided,
the attribution agent calls the ``attribution_calculator`` evaluator skill
script (via Bash, ``--thresholds`` mode) to identify which dimensions fall
below acceptable levels, using that information for attribution reasoning.

The attribution agent reuses the same judge runtime (:meth:`JudgeAgentRuntime.synthesize`),
the same workdir, and the same ambient auth as the per-dimension judges.

Attribution semantics (decision Q11/Q16, spec-aligned, unchanged from the
retired aggregator):

- ``skill_attributions[]`` item = ``{skill_name, usage_status, impact, reason}``;
- candidate ``skill_name`` ∝ ``skill_names`` → ``EvaluationError`` (fail-fast);
- empty list + ``attribution_status="completed"`` is valid;
- ``attribution_status="failed"`` means the agent self-reported inability (score
  preserved, bounded ``attribution_error``) — it does **not** raise.
"""

from __future__ import annotations

import json
import logging
from typing import Any

from pydantic import ValidationError

from evo_agent.evaluator.agent_judge.schemas import (
    AggregatorOutput,
    aggregator_output_validator,
)
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.evaluator.golden_data.skill_provider import SkillProvider

logger = logging.getLogger(__name__)

__all__ = [
    "build_attribution_prompt",
    "judgments_as_text",
    "parse_attribution_output",
]


def build_attribution_prompt(
    judgments: list[Any],
    *,
    skill_names: list[str],
    skill_provider: SkillProvider | None,
    dimension_thresholds: dict[str, float] | None = None,
) -> str:
    """Assemble the attribution agent's prompt.

    The attribution agent performs multi-skill attribution and (when
    ``dimension_thresholds`` are provided) calls the
    ``attribution_calculator`` evaluator skill script to check which
    dimensions fall below their thresholds. ``overall_score`` is produced
    evaluator-side by ``WeightScorer`` — the agent does not compute it.
    """
    verdict_block = "\n".join(judgments_as_text(judgments))
    skill_list = ", ".join(skill_names)
    docs_block = _build_skill_docs_block(skill_names, skill_provider)

    # Threshold-checking block — instructs the agent to call the calculator
    # script in threshold-check mode to identify which dimensions fail.
    threshold_block = ""
    if dimension_thresholds:
        thresholds_json = json.dumps(dimension_thresholds, ensure_ascii=False)
        threshold_block = (
            "\n## 维度阈值校验\n"
            f"用户设定的维度阈值：{thresholds_json}\n\n"
            "使用 ``attribution_calculator`` evaluator skill 脚本做阈值校验：\n"
            "1. Read `attribution_calculator.md` 了解脚本用法\n"
            "2. Bash 执行 `python3 attribution_calculator.py "
            f"--thresholds '{thresholds_json}' --judgments '<各维度分数 JSON>'`\n"
            "3. 解析脚本输出：`checks` 中 `pass=false` 的维度是未达标维度，"
            "`failed` 列出所有未达标维度名\n"
            "4. 未达标维度更可能是问题所在——在归因 `reason` 中引用校验结果，"
            "说明哪些维度未达标及其与 skill 使用的关联\n\n"
        )

    return (
        "你是 Agent 轨迹评估归因 Agent。下面是多个维度的独立评判结果，请综合全部"
        "维度判定 + 轨迹证据，完成多 Skill 归因。\n\n"
        "## 维度评判结果\n"
        f"{verdict_block}\n\n"
        "## 候选 skill 列表（归因目标，只能引用这里的 skill_name）\n"
        f"[{skill_list}]\n\n"
        f"{docs_block}\n"
        f"{threshold_block}"
        "## 完整轨迹与 helper 文档（位于当前工作目录，可用 Read/Grep 读取）\n"
        "- trajectory.jsonl / trajectory.md：全量轨迹（上面的维度判定即基于此）。\n"
        "- 其它 *.md：随附 helper 文档。\n"
        "若 inlined 维度判定信息不足以判定某个 skill 的使用情况，可主动 Read 上述"
        "文件补充证据。\n\n"
        "## 输出要求\n"
        "只输出一个合法 JSON 对象，schema：\n"
        '{"skill_attributions": [{"skill_name": str, '
        '"usage_status": "executed|not_executed|misused|unknown", '
        '"impact": "positive|negative|neutral|none", "reason": str}], '
        '"attribution_status": "completed|failed", '
        '"attribution_error": str|null}\n'
        "- skill_attributions 的 skill_name 必须在候选列表中；无法归因时返回空列表"
        '并设 attribution_status="failed"，在 attribution_error 说明原因。\n'
        "- 禁止 Markdown、code fence、注释、NaN 或 Infinity。"
    )


def judgments_as_text(judgments: list[Any]) -> list[str]:
    """Render each DimensionJudgment as one bullet line."""
    lines: list[str] = []
    for judgment in judgments:
        dimension = getattr(judgment, "dimension", "unknown")
        score = getattr(judgment, "score", 0.0)
        reasoning = getattr(judgment, "reasoning", "")
        lines.append(f"- {dimension}: {score} — {reasoning}")
    return lines


def _build_skill_docs_block(skill_names: list[str], skill_provider: SkillProvider | None) -> str:
    """Best-effort fetch of skill docs for the attribution prompt."""
    if skill_provider is None:
        return "## skill 文档\n（未提供 skill 文档源，仅依据 skill_name 与维度推理归因）"
    blocks: list[str] = []
    for name in skill_names:
        try:
            content = skill_provider.get_skill_content(name)
        except Exception as exc:  # noqa: BLE001 — best-effort doc fetch must not abort
            logger.warning("skill doc fetch failed for %r: %s", name, exc)
            continue
        blocks.append(f"### {name}\n{content}")
    if not blocks:
        return "## skill 文档\n（未取到任何 skill 文档）"
    return "## skill 文档\n" + "\n\n".join(blocks)


def parse_attribution_output(
    data: dict[str, Any],
    *,
    skill_names: list[str],
) -> AggregatorOutput:
    """Validate the attribution agent's dict output → :class:`AggregatorOutput`.

    The runtime's :meth:`synthesize` already extracted the dict from the agent's
    raw stdout (via ``_extract_judgment_dict``); this does the business + pydantic
    validation and the unknown-skill fail-fast. Single-shot — no retry, since a
    subprocess cannot be cheaply re-asked (unlike the retired in-process LLM).
    """
    result = aggregator_output_validator(data)
    if not result.ok:
        raise EvaluationError(
            category="schema_validation_error",
            safe_message=f"attribution output failed validation: {result.message}",
            raw_response=json.dumps(data, ensure_ascii=False),
        )
    try:
        output = AggregatorOutput.model_validate(data)
    except ValidationError as exc:
        raise EvaluationError(
            category="schema_validation_error",
            safe_message=f"attribution output failed validation: {exc}",
            raw_response=json.dumps(data, ensure_ascii=False),
        ) from exc

    known = set(skill_names)
    for attribution in output.skill_attributions:
        if attribution.skill_name not in known:
            raise EvaluationError(
                category="attribution_unknown_skill",
                safe_message=(
                    f"attribution agent attributed unknown skill {attribution.skill_name!r}; "
                    f"known skills: {sorted(known)!r}"
                ),
                raw_response=json.dumps(data, ensure_ascii=False),
            )
    return output
