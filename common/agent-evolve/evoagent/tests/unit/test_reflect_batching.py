"""Reflect 动态组批的公共行为测试。"""

from dataclasses import replace
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from openjiuwen.agent_evolving.dataset import Case, EvaluatedCase
from openjiuwen.agent_evolving.trajectory import Trajectory
from openjiuwen.core.foundation.llm.schema.message import UserMessage

from evo_agent.llm.invocation import LLMInvocation, LLMProviderCapabilities
from evo_agent.optimizer.skill_document.skill_document_optimizer import (
    SkillDocumentOptimizer,
)
from evo_agent.optimizer.skill_document.types import Edit


@pytest.mark.asyncio
async def test_reflect_never_exceeds_minibatch_hard_limit() -> None:
    """即使 token 空间充足，每次 analyst 调用也不得超过 minibatch_size。"""
    optimizer = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)
    optimizer._minibatch_size = 2
    optimizer._format_batch = MagicMock(
        side_effect=lambda batch: ",".join(item[2].case_id for item in batch)
    )
    optimizer._reflect_for_operator = AsyncMock(return_value=[])
    batch = []
    for index in range(5):
        case = Case(
            inputs={"query": str(index)},
            label={"expected": None},
            case_id=f"case-{index}",
        )
        evaluated = EvaluatedCase(case=case, answer={}, score=0.1)
        batch.append((Trajectory(execution_id=f"exec-{index}", steps=[]), evaluated, case))

    await optimizer._reflect(
        formatted_batch="unused",
        skill_content="skill",
        score_threshold=0.5,
        batch_data=batch,
        operator_id="skill-a",
    )

    sent_batches = [
        call.kwargs["formatted_failures"].split(",")
        for call in optimizer._reflect_for_operator.await_args_list
    ]
    assert sent_batches == [
        ["case-0", "case-1"],
        ["case-2", "case-3"],
        ["case-4"],
    ]


def test_reflect_batches_are_split_by_final_prompt_token_budget() -> None:
    """两条各自可容纳但合并后超预算时，必须在调用 provider 前拆批。"""
    optimizer = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)
    optimizer._minibatch_size = 8
    optimizer._llm = LLMInvocation(
        MagicMock(),
        capabilities=LLMProviderCapabilities(280, False, False, False, False, "either"),
        parallelism=1,
        safety_margin_tokens=5,
        chars_per_token=1.0,
        default_output_reserve_tokens=10,
    )
    optimizer._scheduler = MagicMock(max_lr=1)
    optimizer._step_buffer = []
    optimizer._meta_skill_context = ""
    optimizer._build_analyst_prompt = MagicMock(
        side_effect=lambda _name, skill, trajectories, *_args, **_kwargs: (
            f"skill={skill}\n{trajectories}"
        )
    )
    batch = []
    for index in range(2):
        case = Case(
            inputs={"query": f"q{index}"}, label={"expected": None}, case_id=f"case-{index}"
        )
        evaluated = EvaluatedCase(case=case, answer={}, score=0.1)
        batch.append((Trajectory(execution_id=f"exec-{index}", steps=[]), evaluated, case))

    batches = optimizer._build_reflect_batches(
        batch, source_type="failure", skill_content="s", operator_id="skill-a"
    )

    assert len(batches) == 2
    for formatted in batches:
        prompt = optimizer._build_analyst_prompt("analyst_error", "s", formatted, "", "")
        assert optimizer._llm.estimate_messages((UserMessage(content=prompt),)) <= 265


@pytest.mark.asyncio
async def test_bounded_merge_is_hierarchical_and_preserves_source_ids() -> None:
    optimizer = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)
    optimizer._llm = LLMInvocation(
        MagicMock(),
        capabilities=LLMProviderCapabilities(80, False, False, False, False, "either"),
        parallelism=1,
        safety_margin_tokens=5,
        chars_per_token=1.0,
        default_output_reserve_tokens=10,
    )
    optimizer._build_merge_prompts = MagicMock(
        side_effect=lambda edits, *_args: ("".join(edit.content for edit in edits), "retry")
    )

    async def merge_group(edits, *_args):
        source_ids = tuple(source_id for edit in edits for source_id in edit.source_ids)
        return [replace(edits[0], source_ids=source_ids)]

    optimizer._llm_merge_edits = AsyncMock(side_effect=merge_group)
    edits = [
        Edit(op="append", content="x" * 25, source_ids=(f"case-{index}",)) for index in range(5)
    ]

    merged = await optimizer._bounded_merge_edits(edits, "merge_final", "skill", "")

    assert optimizer._llm_merge_edits.await_count >= 3
    assert {source_id for edit in merged for source_id in edit.source_ids} == {
        f"case-{index}" for index in range(5)
    }


@pytest.mark.asyncio
async def test_merge_restores_source_ids_omitted_by_model() -> None:
    optimizer = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)
    optimizer._llm = MagicMock()
    optimizer._model = "model"
    optimizer._build_merge_prompts = MagicMock(return_value=("prompt", "retry"))
    invocation = MagicMock(
        text='{"edits":[{"op":"append","content":"merged"}]}',
        invocation_id="inv-1",
        metadata={},
        finish_reason=None,
    )
    edits = [
        Edit(op="append", content="a", source_ids=("case-a",)),
        Edit(op="append", content="b", source_ids=("case-b",)),
    ]

    with patch(
        "evo_agent.optimizer.skill_document.skill_document_optimizer.invoke_with_retry",
        AsyncMock(return_value=invocation),
    ):
        merged = await optimizer._llm_merge_edits(edits, "merge_final", "skill", "")

    assert set(merged[0].source_ids) == {"case-a", "case-b"}
