"""Tests for experience extraction variance gate."""

from __future__ import annotations

from typing import Any
from unittest.mock import AsyncMock, patch

import pytest

from evo_agent.optimizer.tf_grpo.experience_library import ExperienceLibrary
from evo_agent.optimizer.tf_grpo.semantic_advantage import RolloutSummary
from evo_agent.optimizer.tf_grpo.tf_grpo_optimizer import TfGrpoOptimizer


def _make_opt(*, learn_without_score_variance: bool) -> TfGrpoOptimizer:
    opt = TfGrpoOptimizer.__new__(TfGrpoOptimizer)
    opt._learn_without_score_variance = learn_without_score_variance
    opt._variant_temperature = 1.5
    opt._semantic_advantage_temperature = 0.95
    opt._llm = object()
    opt._model = "mock-model"
    opt._llm_policy = object()
    opt._scenario_name = None
    opt._scenarios_dir = None
    return opt


@pytest.mark.asyncio
async def test_extract_skips_when_no_variance_by_default() -> None:
    opt = _make_opt(learn_without_score_variance=False)
    library = ExperienceLibrary()
    rollouts = [
        RolloutSummary("a", "# a", 0.0, summary="same"),
        RolloutSummary("b", "# b", 0.0, summary="same"),
    ]
    with patch(
        "evo_agent.optimizer.tf_grpo.tf_grpo_optimizer.invoke_text_with_retry",
        new=AsyncMock(),
    ) as invoke:
        log = await TfGrpoOptimizer._extract_and_update_experiences(
            opt,
            rollouts=rollouts,
            library=library,
            operator_id="demo",
            epoch=0,
        )
    assert log == []
    invoke.assert_not_called()


@pytest.mark.asyncio
async def test_extract_proceeds_without_variance_when_enabled() -> None:
    opt = _make_opt(learn_without_score_variance=True)
    library = ExperienceLibrary()
    rollouts = [
        RolloutSummary("a", "# a", 0.01, summary="weak ask"),
        RolloutSummary("b", "# b", 0.01, summary="better clarify"),
    ]

    async def _fake_invoke(*_args: Any, **kwargs: Any) -> str:
        prompt = str(_args[2]) if len(_args) > 2 else str(kwargs.get("prompt") or "")
        # First call: semantic advantage; second: library ops JSON.
        if "操作类型" in prompt or "仅输出 JSON" in prompt:
            return '[{"operation": "Add", "content": "优先澄清期间矛盾后再调工具"}]'
        return "应优先澄清期间矛盾后再调工具"

    with patch(
        "evo_agent.optimizer.tf_grpo.tf_grpo_optimizer.invoke_text_with_retry",
        new=AsyncMock(side_effect=_fake_invoke),
    ) as invoke:
        log = await TfGrpoOptimizer._extract_and_update_experiences(
            opt,
            rollouts=rollouts,
            library=library,
            operator_id="demo",
            epoch=0,
        )
    assert invoke.await_count == 2
    first_kwargs = invoke.await_args_list[0].kwargs
    assert first_kwargs.get("temperature") == 0.95
    second_kwargs = invoke.await_args_list[1].kwargs
    assert second_kwargs.get("temperature") == 0.2
    assert any("Added" in line or "期间" in line for line in log)
    assert len(library.experiences) == 1
