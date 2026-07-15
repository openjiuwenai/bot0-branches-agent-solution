"""C4 (#19): 跨 operator aggregate/select 并行 — 单元测试。"""

from __future__ import annotations

import asyncio
from typing import Any
from unittest.mock import MagicMock

import pytest

from evo_agent.optimizer.skill_document.skill_document_optimizer import (
    SkillDocumentOptimizer,
)
from evo_agent.optimizer.skill_document.types import Edit, Patch, RawPatch


def _make_optimizer(operators: list[str], parallelism: int = 4) -> SkillDocumentOptimizer:
    opt = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)  # type: ignore[no-untyped-call]
    opt._operators = {op: MagicMock() for op in operators}
    opt._semaphore = asyncio.Semaphore(parallelism)
    opt._current_skill_by_operator = {op: f"skill-{op}" for op in operators}
    opt._ranked_patch_by_operator = {}
    opt._artifact_exporter = MagicMock()
    opt._artifact_exporter.enabled = False
    opt._current_epoch = 0
    opt._extract_rejected_edits = MagicMock(return_value=[])  # type: ignore[method-assign]
    opt._sync_skill_to_operator_by_id = MagicMock()  # type: ignore[method-assign]
    return opt


def _raw_patches(n: int, operator_id: str = "") -> list[RawPatch]:
    edits = [Edit(op="replace", content=f"e{i}", target="SKILL.md") for i in range(n)]
    return [RawPatch(patch=Patch(edits=edits), source_type="failure", operator_id=operator_id)]


@pytest.mark.asyncio
async def test_aggregate_select_apply_runs_concurrently_across_operators() -> None:
    """多 operator 的 aggregate+select 并发执行，且并发受 self._semaphore 上限约束。

    mock _aggregate 镜像真实 _llm_merge_edits 路径（内部获取 self._semaphore），
    以验证 C4 的不变式：跨 operator 外层 gather 不再加 semaphore（否则双重获取
    死锁），总并发仍 ≤ parallelism。wait_for 兜底：若外层误加 semaphore 导致
    死锁，测试在 5s 内失败而非挂起。
    """
    parallelism = 2
    opt = _make_optimizer(["op1", "op2", "op3"], parallelism=parallelism)
    active = 0
    peak = 0
    lock = asyncio.Lock()

    async def _aggregate(patches: Any, skill_content: Any) -> Patch:
        nonlocal active, peak
        async with opt._semaphore:  # mirror real invoke_text_with_retry path
            async with lock:
                active += 1
                peak = max(peak, active)
            await asyncio.sleep(0.02)
            async with lock:
                active -= 1
        return Patch(edits=[Edit(op="replace", content="x", target="SKILL.md")])

    async def _select(edits: Any, budget: Any, skill_content: Any) -> list[Edit]:
        return edits[:1]

    opt._aggregate = _aggregate  # type: ignore[method-assign]
    opt._select = _select  # type: ignore[method-assign]

    patches_by_operator = {op: _raw_patches(3) for op in opt._operators}
    (
        n_merged,
        n_selected,
        last_merged,
        last_selected,
    ) = await asyncio.wait_for(
        opt._aggregate_select_apply_all_operators(
            patches_by_operator, budget=2, step=0, artifact_epoch=0
        ),
        timeout=5.0,
    )

    assert peak >= 2  # 跨 operator 并发确实发生
    assert peak <= parallelism  # 总并发不超过 semaphore 上限
    assert set(n_merged) == {"op1", "op2", "op3"}
    assert all(v == 1 for v in n_selected.values())
    assert isinstance(last_merged, Patch)
    assert len(last_selected) == 1


@pytest.mark.asyncio
async def test_aggregate_select_apply_single_operator_output_unchanged() -> None:
    """单 operator 时输出与顺序执行一致。"""
    opt = _make_optimizer(["op1"])
    merged_patch = Patch(edits=[Edit(op="replace", content="m", target="SKILL.md")])

    async def _aggregate(patches: Any, skill_content: Any) -> Patch:
        return merged_patch

    async def _select(edits: Any, budget: Any, skill_content: Any) -> list[Edit]:
        return list(edits)

    opt._aggregate = _aggregate  # type: ignore[method-assign]
    opt._select = _select  # type: ignore[method-assign]

    patches_by_operator = {"op1": _raw_patches(2)}
    (
        n_merged,
        n_selected,
        last_merged,
        last_selected,
    ) = await opt._aggregate_select_apply_all_operators(
        patches_by_operator, budget=5, step=0, artifact_epoch=0
    )

    assert n_merged == {"op1": 1}
    assert n_selected == {"op1": 1}
    assert last_merged is merged_patch
    assert last_selected == merged_patch.edits
    assert opt._ranked_patch_by_operator["op1"].edits == merged_patch.edits


@pytest.mark.asyncio
async def test_aggregate_select_apply_empty_operators() -> None:
    """无 operator 时返回空统计 + 默认 patch。"""
    opt = _make_optimizer([])
    opt._aggregate = MagicMock()  # type: ignore[method-assign]
    opt._select = MagicMock()  # type: ignore[method-assign]
    (
        n_merged,
        n_selected,
        last_merged,
        last_selected,
    ) = await opt._aggregate_select_apply_all_operators({}, budget=2, step=0, artifact_epoch=0)
    assert n_merged == {} and n_selected == {}
    assert last_merged.edits == []
    assert last_selected == []


@pytest.mark.asyncio
async def test_aggregate_select_apply_equivalent_to_serial_reference() -> None:
    """并发路径输出与串行参考路径等价（spec 硬约束：单 operator 输出不变）。

    用相同 mock _aggregate/_select（逐 operator 可区分），分别跑并发
    _aggregate_select_apply_all_operators 与串行 for-loop 参考（镜像 _one 的
    aggregate→select→ranked 写入 + 返回值聚合，跳过 apply 分支——返回元组与
    _ranked_patch_by_operator 不依赖 apply）。若并发引入跨 operator 写污染或
    结果重排，last_merged / n_merged / ranked 状态会与串行参考发散。
    """
    operators = ["op1", "op2", "op3"]
    opt = _make_optimizer(operators, parallelism=2)
    # 逐 operator 可区分的 merged patch（不同 edit 数 + reasoning）
    merged_by_op = {
        op: Patch(
            edits=[
                Edit(op="replace", content=f"m-{op}-{i}", target="SKILL.md") for i in range(idx + 1)
            ],
            reasoning=f"merged-{op}",
        )
        for idx, op in enumerate(operators)
    }

    async def _aggregate(patches: Any, skill_content: Any) -> Patch:
        # 由 patch 的 operator_id 确定性映射到该 op 的 merged patch，
        # 不依赖调用顺序（并发下 _aggregate 调用顺序非确定）
        op = patches[0].operator_id if patches else operators[0]
        return merged_by_op[op]

    async def _select(edits: Any, budget: Any, skill_content: Any) -> list[Edit]:
        return []  # 空 → apply 分支跳过，返回元组与 ranked 状态不依赖 apply

    opt._aggregate = _aggregate  # type: ignore[method-assign]
    opt._select = _select  # type: ignore[method-assign]

    patches_by_operator = {op: _raw_patches(2, operator_id=op) for op in operators}

    # 串行参考路径
    serial_results: list[tuple[str, Patch, list[Edit]]] = []
    for op_id in patches_by_operator:
        skill_content = opt._current_skill_by_operator.get(op_id, "")
        merged = await opt._aggregate(patches_by_operator[op_id], skill_content)
        selected_edits = await opt._select(merged.edits, 2, skill_content)
        ranked = Patch(edits=selected_edits, reasoning=merged.reasoning)
        opt._ranked_patch_by_operator[op_id] = ranked
        opt._artifact_exporter.export_merged_patch(0, 0, merged, operator_id=op_id)
        opt._artifact_exporter.export_selected_edits(0, 0, selected_edits, [], 2, operator_id=op_id)
        serial_results.append((op_id, merged, selected_edits))
    serial_n_merged: dict[str, int] = {}
    serial_n_selected: dict[str, int] = {}
    serial_last_merged = Patch(edits=[], reasoning="no patches")
    serial_last_selected: list[Edit] = []
    for op_id, merged, selected_edits in serial_results:
        serial_n_merged[op_id] = len(merged.edits)
        serial_n_selected[op_id] = len(selected_edits)
        serial_last_merged, serial_last_selected = merged, selected_edits

    # 重置 ranked 状态，跑并发路径
    opt._ranked_patch_by_operator = {}
    (
        n_merged,
        n_selected,
        last_merged,
        last_selected,
    ) = await opt._aggregate_select_apply_all_operators(
        patches_by_operator, budget=2, step=0, artifact_epoch=0
    )

    assert n_merged == serial_n_merged
    assert n_selected == serial_n_selected
    assert last_merged == serial_last_merged
    assert last_selected == serial_last_selected
    assert opt._ranked_patch_by_operator == {
        op: Patch(edits=[], reasoning=f"merged-{op}") for op in operators
    }
