"""Merge and ranking structured output contracts."""

import logging
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from evo_agent.llm.invocation import LLMInvocation, LLMProviderCapabilities
from evo_agent.optimizer.skill_document.skill_document_optimizer import SkillDocumentOptimizer
from evo_agent.optimizer.skill_document.types import Edit


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
    optimizer._current_epoch = 1
    optimizer._global_step = 2
    optimizer._meta_skill_context = ""
    return optimizer


@pytest.mark.asyncio
async def test_merge_repairs_missing_comma_and_preserves_all_source_ids() -> None:
    provider = MagicMock()
    provider.invoke = AsyncMock(
        return_value=MagicMock(
            content=('{"reasoning":"merged" "edits":[{"op":"append","content":"merged rule"}]}')
        )
    )
    edits = [
        Edit(op="append", content="a", source_ids=("case-a",)),
        Edit(op="append", content="b", source_ids=("case-b",)),
    ]

    merged = await _optimizer(provider)._llm_merge_edits(edits, "merge_final", "skill", "")

    assert [edit.content for edit in merged] == ["merged rule"]
    assert set(merged[0].source_ids) == {"case-a", "case-b"}
    assert provider.invoke.await_count == 1


@pytest.mark.asyncio
async def test_ranking_repairs_missing_comma_and_filters_indices_in_model_order() -> None:
    provider = MagicMock()
    provider.invoke = AsyncMock(
        return_value=MagicMock(content='{"reasoning":"ranked" "selected_indices":[2,true,2,0,9]}')
    )
    optimizer = _optimizer(provider)
    edits = [Edit(op="append", content=f"rule-{index}") for index in range(3)]

    selected = await optimizer._select(edits, budget=2, skill_content="skill")

    assert [edit.content for edit in selected] == ["rule-2", "rule-0"]
    assert provider.invoke.await_count == 1


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("stage", "invalid", "fallback"),
    [
        ("merge", '{"edits":{}}', "fallback=original_edits"),
        ("ranking", '{"selected_indices":{}}', "fallback=stable_truncation"),
    ],
)
async def test_validator_exhaustion_logs_final_fallback_diagnostics(
    stage: str,
    invalid: str,
    fallback: str,
    caplog: pytest.LogCaptureFixture,
) -> None:
    provider = MagicMock()
    provider.invoke = AsyncMock(
        side_effect=[MagicMock(content=invalid), MagicMock(content=invalid)]
    )
    optimizer = _optimizer(provider)
    edits = [Edit(op="append", content=f"rule-{index}") for index in range(3)]

    with (
        patch("evo_agent.llm.invocation.asyncio.sleep", new_callable=AsyncMock),
        caplog.at_level(logging.WARNING),
    ):
        if stage == "merge":
            result = await optimizer._llm_merge_edits(edits, "merge_final", "skill", "")
        else:
            result = await optimizer._select(edits, budget=2, skill_content="skill")

    assert result == (edits if stage == "merge" else edits[:2])
    assert f"stage={stage}" in caplog.text
    assert "schema_name=" in caplog.text
    assert "invocation_id=" in caplog.text
    assert "attempt=2" in caplog.text
    assert "category=structure" in caplog.text
    assert fallback in caplog.text
