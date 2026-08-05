"""Mocked TfGrpoOptimizer._backward flow tests."""

from __future__ import annotations

from typing import Any, cast
from unittest.mock import AsyncMock, patch

import pytest
from openjiuwen.agent_evolving.dataset import Case

from evo_agent.optimizer.tf_grpo.tf_grpo_optimizer import TfGrpoOptimizer


class _FakeCaseLoader:
    def __init__(self, n: int = 4) -> None:
        self._cases = [
            Case(inputs={"query": f"q{i}", "i": i}, label={"expected_result": None})
            for i in range(n)
        ]

    def get_cases(self) -> list[Case]:
        return list(self._cases)


class _FakeOperator:
    def __init__(self, content: str) -> None:
        self._content = content
        self.updates: list[str] = []

    def get_state(self) -> dict[str, Any]:
        return {"skill_content": self._content}

    def set_parameter(self, _target: str, value: str) -> None:
        self._content = value
        self.updates.append(value)


class _Exporter:
    def __init__(self) -> None:
        self.experience_exports: list[tuple[int, dict[str, Any], str]] = []

    def export_skill_snapshot(self, *args: Any, **kwargs: Any) -> None:
        return None

    def export_experience_library(
        self,
        epoch: int,
        library: dict[str, Any],
        *,
        operator_id: str = "",
    ) -> None:
        self.experience_exports.append((epoch, library, operator_id))


def _make_opt(*, group_size: int = 2) -> TfGrpoOptimizer:
    from evo_agent.optimizer.tf_grpo.experience_library import ExperienceLibrary

    op = _FakeOperator("---\nname: demo\n---\n\n# Base Skill\n")
    opt = TfGrpoOptimizer.__new__(TfGrpoOptimizer)

    opt._operators = {"demo_skill": op}
    opt._train_cases = _FakeCaseLoader(4)
    opt._group_size = group_size
    opt._cases_per_variant = 2
    opt._variant_temperature = 1.5
    opt._semantic_advantage_temperature = 0.95
    opt._validate_variant_completeness = False
    opt._learn_without_score_variance = False
    opt._rollout_temperature = None
    opt._scenario_name = None
    opt._scenarios_dir = None
    opt._preserve_frontmatter = True
    opt._experience_libs = {
        "demo_skill": ExperienceLibrary(domain="markdown", max_experiences=10)
    }
    opt._artifact_epoch = -1
    opt._current_epoch = -1
    opt._global_step = 0
    opt._curr_epoch_comparison = []
    opt._ranked_patch_by_operator = {}
    opt._llm = object()
    opt._model = "mock-model"
    opt._num_parallel = 2
    opt._phase_callback = None
    opt._adapter_client = object()
    opt._agent = object()
    opt._rollout_extra_data = {}
    opt._conversation_id_factory = None
    opt._trace_max_retries = 1
    opt._trace_retry_backoff = 0.01
    opt._artifact_exporter = _Exporter()

    opt._sample_cases = TfGrpoOptimizer._sample_cases.__get__(opt, TfGrpoOptimizer)
    opt._push_phase = TfGrpoOptimizer._push_phase.__get__(opt, TfGrpoOptimizer)
    opt._on_step_apply = TfGrpoOptimizer._on_step_apply.__get__(opt, TfGrpoOptimizer)
    opt._export_experience_libraries = TfGrpoOptimizer._export_experience_libraries.__get__(
        opt, TfGrpoOptimizer
    )
    setattr(
        opt,
        "_read_skills_from_operators",
        lambda: {k: v.get_state()["skill_content"] for k, v in opt._operators.items()},
    )
    setattr(
        opt,
        "_sync_skill_to_operator_by_id",
        lambda op_id, content: opt._operators[op_id].set_parameter("skill_content", content),
    )
    return opt


@pytest.mark.asyncio
async def test_backward_generates_variants_hot_updates_and_keeps_best() -> None:
    opt = _make_opt(group_size=2)
    op = cast(_FakeOperator, opt._operators["demo_skill"])

    variants = [
        "---\nname: demo\n---\n\n# Variant A\n",
        "---\nname: demo\n---\n\n# Variant B better\n",
    ]
    gen_calls = {"n": 0}

    async def _fake_generate(**kwargs: Any) -> str:
        i = gen_calls["n"]
        gen_calls["n"] += 1
        return variants[i]

    async def _fake_score(cases: list[Case]) -> tuple[float, int, list[Any]]:
        content = op.get_state()["skill_content"]
        score = 0.9 if "better" in content else 0.2
        return score, len(cases), []

    async def _fake_extract(**kwargs: Any) -> list[str]:
        return ["Added: tip"]

    async def _fake_summarize(**kwargs: Any) -> str:
        return f"summary for {kwargs.get('variant_id')}"

    with (
        patch.object(opt, "_generate_variant", new=AsyncMock(side_effect=_fake_generate)),
        patch.object(opt, "_score_variant_on_cases", new=AsyncMock(side_effect=_fake_score)),
        patch.object(opt, "_summarize_rollout", new=AsyncMock(side_effect=_fake_summarize)),
        patch.object(
            opt,
            "_extract_and_update_experiences",
            new=AsyncMock(side_effect=_fake_extract),
        ),
    ):
        await TfGrpoOptimizer._backward(opt, [])

    assert gen_calls["n"] == 2
    assert len(op.updates) >= 2
    assert "better" in op.get_state()["skill_content"]
    assert "better" in opt._last_candidate_skill_by_operator["demo_skill"]
    exporter = cast(_Exporter, opt._artifact_exporter)
    assert len(exporter.experience_exports) == 1
    epoch, library, operator_id = exporter.experience_exports[0]
    assert epoch == 0
    assert operator_id == ""  # single-operator → unprefixed filename
    assert "experiences" in library


@pytest.mark.asyncio
async def test_same_epoch_cases_seed_shared() -> None:
    opt = _make_opt()
    a = opt._sample_cases(2, seed=1)
    b = opt._sample_cases(2, seed=1)
    assert [c.inputs["i"] for c in a] == [c.inputs["i"] for c in b]
