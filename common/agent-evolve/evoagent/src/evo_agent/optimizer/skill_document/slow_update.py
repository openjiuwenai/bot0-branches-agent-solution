# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""Epoch-level slow update: strategic guidance for the skill document's protected region.

Pure async functions. Caller (SkillDocumentOptimizer._run_slow_update) handles
injection into the skill document and operator sync.
"""

from __future__ import annotations

from typing import Any

from openjiuwen.core.common.logging import logger

from evo_agent.llm.invocation import LLMInvocation, LLMInvocationError
from evo_agent.llm.structured_output import (
    StructuredOutputResult,
    log_structured_output,
    parse_structured_output,
)
from evo_agent.optimizer.llm_resilience import (
    LLMInvokePolicy,
    invoke_with_retry,
)
from evo_agent.optimizer.skill_document.prompts import load_skill_opt_prompt
from evo_agent.optimizer.skill_document.structured_validators import (
    SLOW_UPDATE_POLICY,
    validate_slow_update_output,
)
from evo_agent.optimizer.skill_document.types import SlowUpdateResult

_SLOW_UPDATE_POLICY = LLMInvokePolicy(
    attempt_timeout_secs=90,  # A8: 180→90
    total_budget_secs=300,
    max_attempts=2,
)

_RE_THRESHOLD = 0.1  # score delta threshold for regression/improvement
_FAILURE_THRESHOLD = 0.5  # below this = failure
_SUCCESS_THRESHOLD = 0.7  # above this = success


def build_comparison_text(
    prev_epoch_comparison: list[dict[str, Any]],
    curr_epoch_comparison: list[dict[str, Any]],
) -> str:
    """Build longitudinal comparison text for the slow_update/meta_skill LLM prompt.

    Matches cases by case_id across epochs and categorizes into:
    - Regressions: score dropped by >= _RE_THRESHOLD
    - Improvements: score improved by >= _RE_THRESHOLD
    - Persistent failures: both epochs scored below _FAILURE_THRESHOLD
    - Stable successes: both epochs scored >= _SUCCESS_THRESHOLD

    Returns structured markdown for LLM consumption. Empty string when
    either side has no data.
    """
    if not prev_epoch_comparison or not curr_epoch_comparison:
        return ""

    prev_by_id = {c["case_id"]: c for c in prev_epoch_comparison}
    curr_by_id = {c["case_id"]: c for c in curr_epoch_comparison}

    matched_ids = set(prev_by_id) & set(curr_by_id)
    if not matched_ids:
        return ""

    regressions: list[str] = []
    improvements: list[str] = []
    persistent_failures: list[str] = []
    stable_successes: list[str] = []

    for cid in sorted(matched_ids):
        prev_score = prev_by_id[cid].get("curr_score", 0.0)
        curr_score = curr_by_id[cid].get("curr_score", 0.0)
        curr_reason = curr_by_id[cid].get("curr_reason", "")
        delta = curr_score - prev_score

        entry = f"- {cid}: {prev_score:.2f} → {curr_score:.2f} (Δ={delta:+.2f})"
        if curr_reason:
            entry += f"  reason: {curr_reason[:200]}"

        if delta <= -_RE_THRESHOLD:
            regressions.append(entry)
        elif delta >= _RE_THRESHOLD:
            improvements.append(entry)
        elif prev_score < _FAILURE_THRESHOLD and curr_score < _FAILURE_THRESHOLD:
            persistent_failures.append(entry)
        elif prev_score >= _SUCCESS_THRESHOLD and curr_score >= _SUCCESS_THRESHOLD:
            stable_successes.append(entry)
        # Cases with small delta and mixed thresholds are uncategorized (noise)

    lines = [f"## Longitudinal Comparison ({len(matched_ids)} matched cases)\n"]

    lines.append(f"### Regressions ({len(regressions)})")
    lines.extend(regressions if regressions else ["- None"])

    lines.append(f"\n### Improvements ({len(improvements)})")
    lines.extend(improvements if improvements else ["- None"])

    lines.append(f"\n### Persistent Failures ({len(persistent_failures)})")
    lines.extend(persistent_failures if persistent_failures else ["- None"])

    lines.append(f"\n### Stable Successes ({len(stable_successes)})")
    lines.extend(stable_successes if stable_successes else ["- None"])

    return "\n".join(lines)


def _parse_slow_update_response(raw: str) -> SlowUpdateResult:
    """Parse LLM response into SlowUpdateResult. Graceful on failure."""
    if not raw.strip():
        return SlowUpdateResult(reasoning="", slow_update_content="", action="empty_response")
    extraction = parse_structured_output(
        raw,
        policy=SLOW_UPDATE_POLICY,
        validator=validate_slow_update_output,
    )
    parsed = extraction.data
    if parsed is None:
        return SlowUpdateResult(reasoning="", slow_update_content="", action="parse_failed")

    reasoning = parsed.get("reasoning", "")
    content = parsed.get("slow_update_content", "")
    assert isinstance(reasoning, str)
    assert isinstance(content, str)
    return SlowUpdateResult(
        reasoning=reasoning,
        slow_update_content=content,
        action="success" if content else "missing_content",
    )


async def run_slow_update(
    llm: LLMInvocation,
    model: str,
    *,
    prev_skill: str,
    curr_skill: str,
    comparison_text: str,
    prev_guidance: str = "",
) -> SlowUpdateResult:
    """Call slow_update LLM and return strategic guidance.

    Pure async function. Caller handles injection into skill document.
    Returns SlowUpdateResult with empty content on failure (graceful degradation).
    """
    template = load_skill_opt_prompt("slow_update")
    prompt = (
        f"{template}\n\n"
        f"## Previous Epoch Skill\n```markdown\n{prev_skill}\n```\n\n"
        f"## Current Epoch Skill\n```markdown\n{curr_skill}\n```\n\n"
        f"{comparison_text}\n\n"
    )
    if prev_guidance:
        prompt += f"## Previous Slow Update Guidance\n{prev_guidance}\n\n"
    retry_prompt = (
        "格式重试：请依据下方任务证据重新生成真实的 slow update 内容，只输出合法 JSON "
        "对象；reasoning 与 slow_update_content 如出现必须是字符串，不要照抄字段说明。\n\n"
        f"## Previous Epoch Skill\n{prev_skill}\n\n"
        f"## Current Epoch Skill\n{curr_skill}\n\n"
        f"## Longitudinal Evidence\n{comparison_text}\n\n"
    )
    if prev_guidance:
        retry_prompt += f"## Previous Slow Update Guidance\n{prev_guidance}\n"

    def parse(text: str) -> StructuredOutputResult:
        return parse_structured_output(
            text,
            policy=SLOW_UPDATE_POLICY,
            validator=validate_slow_update_output,
        )

    try:
        invocation = await invoke_with_retry(
            llm,
            model,
            prompt,
            policy=_SLOW_UPDATE_POLICY,
            stage="slow_update",
            retry_prompt=retry_prompt,
            result_validator=lambda text: parse(text).data is not None,
            result_error_classifier=lambda text: parse(text).error_category,
            output_schema_name=SLOW_UPDATE_POLICY.schema_name,
        )
    except LLMInvocationError as exc:
        if exc.category == "unusable_response":
            logger.warning(
                "structured output failed stage=slow_update schema_name=slow_update "
                "fallback=parse_failed category=%s",
                exc.output_error_category or "unknown",
            )
            return SlowUpdateResult(reasoning="", slow_update_content="", action="parse_failed")
        if exc.category == "empty_response":
            return SlowUpdateResult(reasoning="", slow_update_content="", action="empty_response")
        logger.warning("slow_update LLM call failed", exc_info=True)
        return SlowUpdateResult(reasoning="", slow_update_content="", action="llm_error")
    except Exception:
        logger.warning("slow_update LLM call failed", exc_info=True)
        return SlowUpdateResult(reasoning="", slow_update_content="", action="llm_error")

    raw = invocation.text
    extraction = parse(raw)
    if extraction.data is not None:
        log_structured_output(
            extraction,
            stage="slow_update",
            schema_name=SLOW_UPDATE_POLICY.schema_name,
            invocation_id=invocation.invocation_id,
            attempt=invocation.metadata.get("attempt", "unknown"),
            finish_reason=invocation.finish_reason or "unknown",
            transport_complete=invocation.transport_complete,
        )
    return _parse_slow_update_response(raw)
