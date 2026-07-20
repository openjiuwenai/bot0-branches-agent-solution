"""Structured output integration from invocation through reflect RawPatch."""

import logging
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from evo_agent.llm.invocation import LLMInvocation, LLMProviderCapabilities
from evo_agent.optimizer.skill_document.skill_document_optimizer import SkillDocumentOptimizer


def _optimizer(provider: MagicMock) -> SkillDocumentOptimizer:
    optimizer = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)
    optimizer._llm = LLMInvocation(
        provider,
        capabilities=LLMProviderCapabilities(32768, False, False, False, False, "either"),
        parallelism=1,
        safety_margin_tokens=1,
        chars_per_token=1.0,
        default_output_reserve_tokens=100,
    )
    optimizer._model = "model"
    optimizer._scheduler = MagicMock(max_lr=3)
    optimizer._step_buffer = []
    optimizer._meta_skill_context = ""
    optimizer._current_epoch = 1
    optimizer._global_step = 2
    return optimizer


@pytest.mark.asyncio
async def test_reflect_repairs_fenced_missing_comma_and_records_provenance() -> None:
    provider = MagicMock()
    provider.invoke = AsyncMock(
        return_value=MagicMock(
            content=(
                '```json\n{"patch":{"reasoning":"r" "edits":'
                '[{"op":"append","content":"rule"}]}}\n```'
            )
        )
    )

    patches = await _optimizer(provider)._reflect_for_operator(
        operator_id="skill-a",
        formatted_failures="failed trajectory",
        formatted_successes="",
        skill_content="skill",
        source_ids=("case-1",),
    )

    assert len(patches) == 1
    assert patches[0].patch.edits[0].content == "rule"
    assert patches[0].repaired is True
    assert patches[0].parse_mode == "deterministic_comma_repair"
    assert [item["op"] for item in patches[0].repair_operations] == [
        "strip_code_fence",
        "insert_comma",
    ]
    assert provider.invoke.await_count == 1


@pytest.mark.asyncio
async def test_reflect_format_retry_consumes_only_the_second_patch() -> None:
    provider = MagicMock()
    provider.invoke = AsyncMock(
        side_effect=[
            MagicMock(content='{"patch":'),
            MagicMock(
                content=(
                    '{"patch":{"reasoning":"second","edits":'
                    '[{"op":"append","content":"second rule"}]}}'
                )
            ),
        ]
    )

    patches = await _optimizer(provider)._reflect_for_operator(
        operator_id="skill-a",
        formatted_failures="failed trajectory",
        formatted_successes="",
        skill_content="skill",
    )

    assert [edit.content for edit in patches[0].patch.edits] == ["second rule"]
    assert patches[0].patch.reasoning == "second"
    assert provider.invoke.await_count == 2


@pytest.mark.asyncio
async def test_reflect_validator_exhaustion_logs_final_discard_diagnostics(
    caplog: pytest.LogCaptureFixture,
) -> None:
    provider = MagicMock()
    provider.invoke = AsyncMock(
        side_effect=[
            MagicMock(content='```json\n{"patch":{"edits":{}}}\n```'),
            MagicMock(content='```json\n{"patch":{"edits":{}}}\n```'),
        ]
    )

    with (
        patch("evo_agent.llm.invocation.asyncio.sleep", new_callable=AsyncMock),
        caplog.at_level(logging.WARNING),
    ):
        patches = await _optimizer(provider)._reflect_for_operator(
            operator_id="skill-a",
            formatted_failures="failed trajectory",
            formatted_successes="",
            skill_content="skill",
        )

    assert patches == []
    assert "stage=reflect" in caplog.text
    assert "schema_name=reflect_failure" in caplog.text
    assert "invocation_id=" in caplog.text
    assert "attempt=2" in caplog.text
    assert "category=structure" in caplog.text
    assert "fallback=discard_patch" in caplog.text
