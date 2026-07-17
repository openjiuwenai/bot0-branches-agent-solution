"""C3 (#7): operator 内 failure/success merge 并行 — 单元测试。"""

from __future__ import annotations

import asyncio
from typing import Any
from unittest.mock import MagicMock

import pytest

from evo_agent.optimizer.skill_document.skill_document_optimizer import (
    SkillDocumentOptimizer,
)
from evo_agent.optimizer.skill_document.types import Edit, Patch, RawPatch


def _make_optimizer(parallelism: int = 4) -> SkillDocumentOptimizer:
    opt = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)  # type: ignore[no-untyped-call]
    opt._semaphore = asyncio.Semaphore(parallelism)
    opt._meta_skill_context = ""
    return opt


def _patch(source: str, n_edits: int) -> RawPatch:
    edits = [
        Edit(op="replace", content=f"{source}-e{i}", target="SKILL.md") for i in range(n_edits)
    ]
    return RawPatch(patch=Patch(edits=edits), source_type=source)


def _patches() -> list[RawPatch]:
    # 2 failure patches (2 edits each) + 2 success patches (2 edits each) = 8 edits
    return [_patch("failure", 2), _patch("failure", 2), _patch("success", 2), _patch("success", 2)]


@pytest.mark.asyncio
async def test_aggregate_stage1_and_stage2_run_concurrently() -> None:
    """Stage 1 (merge failure) 与 Stage 2 (merge success) 并发执行。"""
    opt = _make_optimizer(parallelism=4)
    active = 0
    peak = 0
    lock = asyncio.Lock()
    call_order: list[str] = []

    async def _merge(edits: list[Edit], template: str, *args: Any, **kw: Any) -> list[Edit]:
        nonlocal active, peak
        async with lock:
            active += 1
            peak = max(peak, active)
            call_order.append(template)
        await asyncio.sleep(0.02)
        async with lock:
            active -= 1
        return edits  # 原样返回，仅验证并发

    opt._llm_merge_edits = _merge  # type: ignore[method-assign]
    opt._format_meta_skill_context = MagicMock(return_value="")  # type: ignore[method-assign]
    opt._rule_dedup_edits = MagicMock(side_effect=lambda e: e)  # type: ignore[method-assign]

    result = await opt._aggregate(_patches(), skill_content="SKILL")

    assert peak >= 2  # Stage 1 + Stage 2 同时在跑
    # Stage 3 (merge_final) 在 1+2 之后
    assert "merge_failure" in call_order and "merge_success" in call_order
    assert "merge_final" in call_order
    assert call_order.index("merge_final") > 0
    assert isinstance(result, Patch)


@pytest.mark.asyncio
async def test_aggregate_parallel_merge_result_consistent() -> None:
    """并发 merge 的输出与顺序一致：failure/success 各自合并后 final 合并。"""
    opt = _make_optimizer(parallelism=4)

    merge_calls: dict[str, list[Edit]] = {}

    async def _merge(edits: list[Edit], template: str, *args: Any, **kw: Any) -> list[Edit]:
        merge_calls[template] = list(edits)
        await asyncio.sleep(0)
        # 各阶段返回首条 edit 作为合并结果
        return edits[:1]

    opt._llm_merge_edits = _merge  # type: ignore[method-assign]
    opt._format_meta_skill_context = MagicMock(return_value="")  # type: ignore[method-assign]
    opt._rule_dedup_edits = MagicMock(side_effect=lambda e: e)  # type: ignore[method-assign]

    result = await opt._aggregate(_patches(), skill_content="SKILL")

    # Stage 1 收到 4 条 failure edits，Stage 2 收到 4 条 success edits
    assert len(merge_calls["merge_failure"]) == 4
    assert len(merge_calls["merge_success"]) == 4
    # final 收到 failure_merged (1) + success_merged (1) = 2 → <=3 不会再调 final?
    # failure_merged=1 + success_merged=1 = 2, combined=2 <=3 → 直接返回，不调 merge_final
    assert len(result.edits) == 2


@pytest.mark.asyncio
async def test_aggregate_bounds_concurrency_to_semaphore() -> None:
    """两个 merge 并发，但经 self._semaphore 约束（≤ parallelism）。"""
    opt = _make_optimizer(parallelism=1)
    active = 0
    peak = 0
    lock = asyncio.Lock()

    async def _merge(edits: list[Edit], template: str, *args: Any, **kw: Any) -> list[Edit]:
        nonlocal active, peak
        async with opt._semaphore:
            async with lock:
                active += 1
                peak = max(peak, active)
            await asyncio.sleep(0.01)
            async with lock:
                active -= 1
        return edits

    opt._llm_merge_edits = _merge  # type: ignore[method-assign]
    opt._format_meta_skill_context = MagicMock(return_value="")  # type: ignore[method-assign]
    opt._rule_dedup_edits = MagicMock(side_effect=lambda e: e)  # type: ignore[method-assign]

    await opt._aggregate(_patches(), skill_content="SKILL")
    assert peak <= 1
