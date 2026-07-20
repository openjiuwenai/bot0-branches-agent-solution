"""Slow-update structured output behavior tests."""

from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from evo_agent.llm.invocation import LLMInvocation, LLMProviderCapabilities
from evo_agent.optimizer.skill_document.slow_update import run_slow_update


def _invocation(provider: MagicMock) -> LLMInvocation:
    return LLMInvocation(
        provider,
        capabilities=LLMProviderCapabilities(32768, False, False, False, False, "either"),
        parallelism=1,
        safety_margin_tokens=1,
        chars_per_token=1.0,
        default_output_reserve_tokens=100,
    )


@pytest.mark.asyncio
async def test_slow_update_repairs_one_missing_comma_without_retry() -> None:
    provider = MagicMock()
    provider.invoke = AsyncMock(
        return_value=MagicMock(content='{"reasoning": "trend" "slow_update_content": "keep this"}')
    )

    result = await run_slow_update(
        _invocation(provider),
        "model",
        prev_skill="old",
        curr_skill="new",
        comparison_text="comparison",
    )

    assert result.action == "success"
    assert result.reasoning == "trend"
    assert result.slow_update_content == "keep this"
    assert provider.invoke.await_count == 1


@pytest.mark.asyncio
async def test_slow_update_retries_invalid_type_and_uses_second_response() -> None:
    provider = MagicMock()
    provider.invoke = AsyncMock(
        side_effect=[
            MagicMock(content='{"slow_update_content": 42}'),
            MagicMock(content='{"reasoning":"r","slow_update_content":"second"}'),
        ]
    )

    with patch("evo_agent.llm.invocation.asyncio.sleep", new_callable=AsyncMock):
        result = await run_slow_update(
            _invocation(provider),
            "model",
            prev_skill="old",
            curr_skill="new",
            comparison_text="comparison",
        )

    assert result.action == "success"
    assert result.slow_update_content == "second"
    assert provider.invoke.await_count == 2
    retry_prompt = provider.invoke.await_args_list[1].args[0][0].content
    assert "old" in retry_prompt
    assert "new" in retry_prompt
    assert "comparison" in retry_prompt


@pytest.mark.asyncio
async def test_slow_update_keeps_missing_content_and_parse_failed_distinct() -> None:
    missing_provider = MagicMock()
    missing_provider.invoke = AsyncMock(return_value=MagicMock(content='{"reasoning":"none"}'))
    invalid_provider = MagicMock()
    invalid_provider.invoke = AsyncMock(return_value=MagicMock(content='{"reasoning": 42}'))

    missing = await run_slow_update(
        _invocation(missing_provider),
        "model",
        prev_skill="old",
        curr_skill="new",
        comparison_text="comparison",
    )
    with patch("evo_agent.llm.invocation.asyncio.sleep", new_callable=AsyncMock):
        invalid = await run_slow_update(
            _invocation(invalid_provider),
            "model",
            prev_skill="old",
            curr_skill="new",
            comparison_text="comparison",
        )

    assert missing.action == "missing_content"
    assert invalid.action == "parse_failed"
