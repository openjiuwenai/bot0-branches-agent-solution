"""LLM aggregator — fuse per-dimension verdicts into a score + skill attribution.

The aggregator is the **non-spawn** half of agent-as-judge: after the per-dim
subprocess runs each emit a :class:`DimensionJudgment`, this module makes one
LLM call (reusing :class:`LLMInvocation.invoke_sync` + :func:`parse_structured_output`)
that reads all reasonings plus the candidate ``skill_names`` and their docs and
emits the **plural skill attribution** only. The ``overall_score`` is produced
separately by a deterministic :class:`WeightScorer` (an evaluator-side script,
not an LLM judgment) and merged into the final :class:`AggregatorOutput`.

Threading: ``invoke_sync`` raises if called on the invocation event loop, so
``aggregate`` must run in a worker thread — the HTTP route's
``asyncio.to_thread`` guarantees this (mirrors ``LLMEvaluator``).

Attribution semantics (decision Q11/Q16, spec-aligned):

- ``skill_attributions[]`` item = ``{skill_name, usage_status, impact, reason}``;
- candidate ``skill_name`` ∉ ``skill_names`` → ``EvaluationError`` (fail-fast);
- empty list + ``attribution_status="completed"`` is valid;
- ``attribution_status="failed"`` means the LLM self-reported inability (score
  preserved, bounded ``attribution_error``) — it does **not** raise.
"""

from __future__ import annotations

import logging
from typing import Any

from openjiuwen.core.foundation.llm import UserMessage
from pydantic import ValidationError

from evo_agent.evaluator.agent_judge.schemas import (
    AggregatorOutput,
    aggregator_output_validator,
)
from evo_agent.evaluator.agent_judge.scorers import WeightScorer
from evo_agent.evaluator.domain.scoring import EvaluationError
from evo_agent.evaluator.golden_data.skill_provider import SkillProvider
from evo_agent.llm.invocation import (
    LLMInvocation,
    LLMInvocationContext,
    LLMInvocationError,
    LLMInvocationRequest,
    LLMRetryPolicy,
)
from evo_agent.llm.structured_output import (
    StructuredOutputPolicy,
    parse_structured_output,
)

logger = logging.getLogger(__name__)

__all__ = ["SkillAggregator"]

_AGG_JSON_KEYS = frozenset(
    {"overall_score", "skill_attributions", "attribution_status", "attribution_error"}
)
_AGG_POLICY = StructuredOutputPolicy(
    schema_name="agent_judge_aggregator",
    required_keys=frozenset({"attribution_status"}),
    allowed_comma_next_keys=_AGG_JSON_KEYS,
)


class SkillAggregator:
    """Fuse dimension judgments into an overall score + skill attribution."""

    def __init__(
        self,
        invocation: LLMInvocation,
        *,
        scorer: WeightScorer,
        reserved_output_tokens: int = 2048,
        skill_provider: SkillProvider | None = None,
    ) -> None:
        self._invocation = invocation
        self._scorer = scorer
        self._reserved_output_tokens = reserved_output_tokens
        self._skill_provider = skill_provider

    def aggregate(
        self,
        judgments: list[Any],
        *,
        skill_names: list[str],
        weights: dict[str, float],
        case_id: str,
    ) -> AggregatorOutput:
        """Run the aggregator LLM call; raise ``EvaluationError`` on infra
        failure or unknown-skill attribution (fail-fast)."""
        prompt = _build_aggregator_prompt(
            judgments,
            skill_names=skill_names,
            weights=weights,
            skill_provider=self._skill_provider,
        )
        retry_prompt = _build_retry_prompt(prompt)

        invalid_error: EvaluationError | None = None

        def _try_parse(text: str) -> AggregatorOutput:
            nonlocal invalid_error
            try:
                return _parse_aggregator(text, skill_names=skill_names)
            except EvaluationError as error:
                invalid_error = error
                raise

        def _result_is_valid(text: str) -> bool:
            try:
                _try_parse(text)
            except EvaluationError:
                return False
            return True

        def _result_error_category(text: str) -> str | None:
            try:
                _try_parse(text)
            except EvaluationError as error:
                return error.category
            return None

        try:
            result = self._invocation.invoke_sync(
                LLMInvocationRequest(
                    stage="evaluator",
                    messages=(UserMessage(content=prompt),),
                    retry_messages=(UserMessage(content=retry_prompt),),
                    result_validator=_result_is_valid,
                    result_error_classifier=_result_error_category,
                    context=LLMInvocationContext(run_id="agent_judge", case_id=case_id),
                    retry_policy=LLMRetryPolicy(2, 120.0, 300.0, 1.0, 0.0),
                    output_schema_name="agent_judge_aggregator",
                    reserved_output_tokens=self._reserved_output_tokens,
                )
            )
            response = result.text
        except LLMInvocationError as exc:
            if exc.category == "unusable_response" and invalid_error is not None:
                raise invalid_error from exc
            raise EvaluationError(
                category=exc.category or "llm_invoke_error",
                safe_message=f"aggregator LLM failed for case {case_id}: {exc}",
            ) from exc
        except Exception as exc:
            category = getattr(exc, "category", None)
            raise EvaluationError(
                category=category if isinstance(category, str) else "llm_invoke_error",
                safe_message=f"aggregator LLM failed for case {case_id}: {exc}",
            ) from exc

        # overall_score comes from the deterministic evaluator-side scorer, not
        # the LLM; the LLM's job is attribution only. ``parsed.overall_score``
        # (a 0.0 placeholder when the LLM omits the field) is discarded.
        overall = self._scorer.score(judgments, weights)
        parsed = _try_parse(response)
        return AggregatorOutput(
            overall_score=overall,
            skill_attributions=parsed.skill_attributions,
            attribution_status=parsed.attribution_status,
            attribution_error=parsed.attribution_error,
        )


def _parse_aggregator(raw: str, *, skill_names: list[str]) -> AggregatorOutput:
    """Parse + validate the aggregator output; raise ``EvaluationError`` on failure."""
    extraction = parse_structured_output(
        raw,
        policy=_AGG_POLICY,
        validator=aggregator_output_validator,
    )
    data = extraction.data
    if data is None:
        raise EvaluationError(
            category=extraction.error_category or "json_parse_error",
            safe_message=f"failed to extract aggregator JSON: {extraction.error}",
            raw_response=raw,
        )
    # overall_score is scorer-sourced; the LLM no longer emits it (the model
    # field defaults to 0.0 when absent). Any LLM-emitted value is ignored by
    # ``aggregate`` in favor of the deterministic scorer result.
    try:
        output = AggregatorOutput.model_validate(data)
    except ValidationError as exc:
        raise EvaluationError(
            category="schema_validation_error",
            safe_message=f"aggregator output failed validation: {exc}",
            raw_response=raw,
        ) from exc

    known = set(skill_names)
    for attribution in output.skill_attributions:
        if attribution.skill_name not in known:
            raise EvaluationError(
                category="attribution_unknown_skill",
                safe_message=(
                    f"aggregator attributed unknown skill {attribution.skill_name!r}; "
                    f"known skills: {sorted(known)!r}"
                ),
                raw_response=raw,
            )
    return output


def _build_aggregator_prompt(
    judgments: list[Any],
    *,
    skill_names: list[str],
    weights: dict[str, float],
    skill_provider: SkillProvider | None,
) -> str:
    """Assemble the aggregator prompt: dimension verdicts + skill list + docs."""
    verdict_block = "\n".join(judgments_as_text(judgments))
    skill_list = ", ".join(skill_names)
    weights_block = ", ".join(f"{name}: {weight}" for name, weight in weights.items())
    docs_block = _build_skill_docs_block(skill_names, skill_provider)

    return (
        "你是 Agent 轨迹评估聚合器。下面是多个维度的独立评判结果，请只做"
        "多 skill 归因（总体分数 overall_score 由权重计算脚本另行计算，你无需给出）。\n\n"
        "## 维度评判结果\n"
        f"{verdict_block}\n\n"
        "## 候选 skill 列表（归因目标，只能引用这里的 skill_name）\n"
        f"[{skill_list}]\n\n"
        f"{docs_block}\n\n"
        "## 维度权重（参考：权重高 = 更有影响力的维度，归因时优先关注"
        "这些维度里 skill 的使用情况）\n"
        f"{weights_block}\n\n"
        "## 输出要求\n"
        "只输出一个合法 JSON 对象，schema：\n"
        '{"skill_attributions": [{"skill_name": str, '
        '"usage_status": "executed|not_executed|misused|unknown", '
        '"impact": "positive|negative|neutral|none", "reason": str}], '
        '"attribution_status": "completed|failed", '
        '"attribution_error": str|null}\n'
        "- 不要输出 overall_score（由权重脚本计算）。\n"
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
    """Best-effort fetch of skill docs for the aggregator prompt."""
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


def _build_retry_prompt(prompt: str) -> str:
    return (
        f"{prompt}\n\n格式重试：上一次输出未通过 JSON schema 校验。"
        "请重新输出一个合法 JSON 对象；必填字段：attribution_status；"
        "不要输出 overall_score（由权重脚本计算）；"
        "skill_attributions 中每个 skill_name 必须在候选列表中。"
        "禁止 Markdown、code fence、注释、NaN 或 Infinity。"
    )
