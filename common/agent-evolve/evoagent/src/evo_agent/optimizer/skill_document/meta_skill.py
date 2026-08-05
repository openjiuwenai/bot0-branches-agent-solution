# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""Epoch-level meta skill: optimizer-side memory for future edit generation.

Pure async function. Caller (SkillDocumentOptimizer._run_meta_skill) stores
the result in _meta_skill_context. Does not modify the skill document.
"""

from __future__ import annotations

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
    META_SKILL_POLICY,
    validate_meta_skill_output,
)

_META_SKILL_POLICY = LLMInvokePolicy(
    attempt_timeout_secs=90,  # A8: 180→90
    total_budget_secs=300,
    max_attempts=2,
)


def _parse_meta_skill_response(raw: str) -> str:
    """Parse LLM response and extract meta_skill_content. Returns empty string on failure."""
    if not raw.strip():
        return ""
    extraction = parse_structured_output(
        raw,
        policy=META_SKILL_POLICY,
        validator=validate_meta_skill_output,
    )
    parsed = extraction.data
    if parsed is None:
        return ""
    content = parsed.get("meta_skill_content", "")
    assert isinstance(content, str)
    return content


async def run_meta_skill(
    llm: LLMInvocation,
    model: str,
    *,
    prev_skill: str,
    curr_skill: str,
    comparison_text: str,
    prev_meta_skill: str = "",
) -> str:
    """Call meta_skill LLM and return optimizer-side memory content.

    Returns the meta_skill_content string from the LLM JSON response.
    Returns empty string on failure (graceful degradation).
    """
    template = load_skill_opt_prompt("meta_skill")
    prompt = (
        f"{template}\n\n"
        f"## Previous Epoch Skill\n```markdown\n{prev_skill}\n```\n\n"
        f"## Current Epoch Skill\n```markdown\n{curr_skill}\n```\n\n"
        f"{comparison_text}\n\n"
    )
    if prev_meta_skill:
        prompt += f"## Previous Meta Skill\n{prev_meta_skill}\n\n"
    retry_prompt = (
        "格式重试：请依据下方任务证据重新生成真实的 meta skill 内容，只输出合法 JSON "
        "对象；reasoning 与 meta_skill_content 如出现必须是字符串，不要照抄字段说明。\n\n"
        f"## Previous Epoch Skill\n{prev_skill}\n\n"
        f"## Current Epoch Skill\n{curr_skill}\n\n"
        f"## Longitudinal Evidence\n{comparison_text}\n\n"
    )
    if prev_meta_skill:
        retry_prompt += f"## Previous Meta Skill\n{prev_meta_skill}\n"

    def parse(text: str) -> StructuredOutputResult:
        return parse_structured_output(
            text,
            policy=META_SKILL_POLICY,
            validator=validate_meta_skill_output,
        )

    try:
        invocation = await invoke_with_retry(
            llm,
            model,
            prompt,
            policy=_META_SKILL_POLICY,
            stage="meta_skill",
            retry_prompt=retry_prompt,
            result_validator=lambda text: parse(text).data is not None,
            result_error_classifier=lambda text: parse(text).error_category,
            output_schema_name=META_SKILL_POLICY.schema_name,
        )
    except LLMInvocationError as exc:
        logger.warning(
            "structured output failed stage=meta_skill schema_name=meta_skill "
            "fallback=keep_existing category=%s",
            exc.output_error_category or exc.category,
        )
        return ""
    except Exception:
        logger.warning("meta_skill LLM call failed", exc_info=True)
        return ""

    raw = invocation.text
    extraction = parse(raw)
    if extraction.data is not None:
        log_structured_output(
            extraction,
            stage="meta_skill",
            schema_name=META_SKILL_POLICY.schema_name,
            invocation_id=invocation.invocation_id,
            attempt=invocation.metadata.get("attempt", "unknown"),
            finish_reason=invocation.finish_reason or "unknown",
            transport_complete=invocation.transport_complete,
        )
    return _parse_meta_skill_response(raw)
