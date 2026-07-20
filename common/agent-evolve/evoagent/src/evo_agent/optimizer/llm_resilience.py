# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""Compatibility helpers routed exclusively through the unified invocation Module."""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from typing import Any

from openjiuwen.core.foundation.llm.schema.message import UserMessage

from evo_agent.llm.invocation import (
    LLMInvocation,
    LLMInvocationContext,
    LLMInvocationRequest,
    LLMInvocationResult,
    LLMRetryPolicy,
    LLMStage,
)


@dataclass(frozen=True)
class LLMInvokePolicy:
    """Policy for a single evolution-layer LLM invocation."""

    attempt_timeout_secs: float
    total_budget_secs: float
    max_attempts: int = 2
    backoff_base_secs: float = 1.0
    retry_empty_response: bool = True


async def invoke_text_with_retry(
    llm: LLMInvocation,
    model: str,
    prompt: str,
    *,
    policy: LLMInvokePolicy,
    stage: LLMStage = "reflect",
    retry_prompt: str | None = None,
    temperature: float | None = None,
    is_result_usable: Callable[[str], bool] | None = None,
    result_validator: Callable[[str], bool] | None = None,
    result_error_classifier: Callable[[str], str | None] | None = None,
    output_schema_name: str | None = None,
    context: LLMInvocationContext | None = None,
    **kwargs: Any,
) -> str:
    """Return text while retaining budgeting/retry/limiting in ``LLMInvocation``."""
    raw, _ = await invoke_text_with_retry_and_prompt(
        llm=llm,
        model=model,
        prompt=prompt,
        policy=policy,
        stage=stage,
        retry_prompt=retry_prompt,
        temperature=temperature,
        is_result_usable=is_result_usable,
        result_validator=result_validator,
        result_error_classifier=result_error_classifier,
        output_schema_name=output_schema_name,
        context=context,
        **kwargs,
    )
    return raw


async def invoke_with_retry(
    llm: LLMInvocation,
    model: str,
    prompt: str,
    *,
    policy: LLMInvokePolicy,
    stage: LLMStage = "reflect",
    retry_prompt: str | None = None,
    temperature: float | None = None,
    is_result_usable: Callable[[str], bool] | None = None,
    result_validator: Callable[[str], bool] | None = None,
    result_error_classifier: Callable[[str], str | None] | None = None,
    output_schema_name: str | None = None,
    context: LLMInvocationContext | None = None,
    **kwargs: Any,
) -> LLMInvocationResult:
    """Return the complete provider-neutral result for diagnostic-aware callers."""
    del model, temperature, kwargs
    if not isinstance(llm, LLMInvocation):
        raise TypeError("optimizer LLM calls require the run-scoped LLMInvocation")
    retry_messages = (UserMessage(content=retry_prompt),) if retry_prompt is not None else None
    if result_validator is not None and is_result_usable is not None:
        raise ValueError("pass result_validator or is_result_usable, not both")
    validator = result_validator or is_result_usable
    return await llm.invoke(
        LLMInvocationRequest(
            stage=stage,
            messages=(UserMessage(content=prompt),),
            retry_messages=retry_messages,
            result_validator=validator,
            result_error_classifier=result_error_classifier,
            output_schema_name=output_schema_name,
            context=context or LLMInvocationContext(run_id="optimizer"),
            retry_policy=LLMRetryPolicy(
                max_attempts=policy.max_attempts,
                attempt_timeout_secs=policy.attempt_timeout_secs,
                total_budget_secs=policy.total_budget_secs,
                backoff_base_secs=policy.backoff_base_secs,
                backoff_jitter_secs=0.0,
            ),
        )
    )


async def invoke_text_with_retry_and_prompt(
    llm: LLMInvocation,
    model: str,
    prompt: str,
    *,
    policy: LLMInvokePolicy,
    stage: LLMStage = "reflect",
    retry_prompt: str | None = None,
    temperature: float | None = None,
    is_result_usable: Callable[[str], bool] | None = None,
    result_validator: Callable[[str], bool] | None = None,
    result_error_classifier: Callable[[str], str | None] | None = None,
    output_schema_name: str | None = None,
    context: LLMInvocationContext | None = None,
    **kwargs: Any,
) -> tuple[str, str]:
    """Invoke through the sole seam and report which prompt produced the result."""
    result = await invoke_with_retry(
        llm,
        model,
        prompt,
        policy=policy,
        stage=stage,
        retry_prompt=retry_prompt,
        temperature=temperature,
        is_result_usable=is_result_usable,
        result_validator=result_validator,
        result_error_classifier=result_error_classifier,
        output_schema_name=output_schema_name,
        context=context,
        **kwargs,
    )
    used_prompt = retry_prompt if result.metadata.get("attempt", 1) > 1 and retry_prompt else prompt
    return result.text, used_prompt


__all__ = [
    "LLMInvokePolicy",
    "invoke_text_with_retry",
    "invoke_text_with_retry_and_prompt",
    "invoke_with_retry",
]
