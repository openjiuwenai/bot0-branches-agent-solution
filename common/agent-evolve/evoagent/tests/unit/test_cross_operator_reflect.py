"""C2 (#3): 跨 operator reflect 并行 — 单元测试。"""

from __future__ import annotations

import asyncio
from typing import Any
from unittest.mock import AsyncMock, MagicMock

import pytest

from evo_agent.optimizer.skill_document.skill_document_optimizer import (
    SkillDocumentOptimizer,
)
from evo_agent.optimizer.skill_document.types import (
    AttributedBatch,
    Patch,
    RawPatch,
)


def _make_optimizer(operators: list[str], parallelism: int) -> SkillDocumentOptimizer:
    opt = SkillDocumentOptimizer.__new__(SkillDocumentOptimizer)  # type: ignore[no-untyped-call]
    opt._operators = {op: MagicMock() for op in operators}
    opt._semaphore = asyncio.Semaphore(parallelism)
    opt._score_threshold = 0.5
    opt._current_skill_by_operator = {op: f"skill-{op}" for op in operators}
    opt._current_epoch = 0
    return opt


def _patch(op_id: str) -> RawPatch:
    return RawPatch(patch=Patch(edits=[]), source_type="failure", operator_id=op_id)


@pytest.mark.asyncio
async def test_reflect_all_operators_runs_concurrently() -> None:
    """多 operator 的 _reflect 并发执行（非顺序）。"""
    opt = _make_optimizer(["op1", "op2", "op3"], parallelism=4)
    active = 0
    peak = 0
    lock = asyncio.Lock()

    async def _reflect(**kwargs: Any) -> list[RawPatch]:
        nonlocal active, peak
        async with lock:
            active += 1
            peak = max(peak, active)
        await asyncio.sleep(0.02)
        async with lock:
            active -= 1
        return [_patch(kwargs["operator_id"])]

    opt._reflect = _reflect  # type: ignore[method-assign]
    opt._format_batch = MagicMock(return_value="fmt")  # type: ignore[method-assign]
    opt._validate_raw_patch_operator_id = MagicMock(side_effect=lambda p, s: p)  # type: ignore[method-assign]

    attributed = {
        op: AttributedBatch(operator_id=op, failures=[], successes=[]) for op in opt._operators
    }
    result = await opt._reflect_all_operators(attributed, step=0, accumulation=0)

    assert peak >= 2  # 至少两个 operator 并发
    assert set(result) == {"op1", "op2", "op3"}
    for op in opt._operators:
        assert len(result[op]) == 1


@pytest.mark.asyncio
async def test_reflect_all_operators_bounds_llm_concurrency() -> None:
    """每个 _reflect 内部经 self._semaphore，总并发不超 parallelism。"""
    opt = _make_optimizer(["op1", "op2", "op3", "op4"], parallelism=2)
    active = 0
    peak = 0
    lock = asyncio.Lock()

    async def _reflect(**kwargs: Any) -> list[RawPatch]:
        nonlocal active, peak
        # 模拟 run_analyst 内部获取 self._semaphore（真实路径通过 invoke_text_with_retry）
        async with opt._semaphore:
            async with lock:
                active += 1
                peak = max(peak, active)
            await asyncio.sleep(0.01)
            async with lock:
                active -= 1
        return [_patch(kwargs["operator_id"])]

    opt._reflect = _reflect  # type: ignore[method-assign]
    opt._format_batch = MagicMock(return_value="fmt")  # type: ignore[method-assign]
    opt._validate_raw_patch_operator_id = MagicMock(side_effect=lambda p, s: p)  # type: ignore[method-assign]

    attributed = {
        op: AttributedBatch(operator_id=op, failures=[], successes=[]) for op in opt._operators
    }
    await opt._reflect_all_operators(attributed, step=0, accumulation=0)
    assert peak <= 2  # 总并发不超过 parallelism


@pytest.mark.asyncio
async def test_reflect_all_operators_empty_operators() -> None:
    """无 operator 时返回空 dict。"""
    opt = _make_optimizer([], parallelism=4)
    opt._reflect = AsyncMock(return_value=[])  # type: ignore[method-assign]
    opt._format_batch = MagicMock(return_value="")  # type: ignore[method-assign]
    opt._validate_raw_patch_operator_id = MagicMock(side_effect=lambda p, s: p)  # type: ignore[method-assign]
    result = await opt._reflect_all_operators({}, step=0, accumulation=0)
    assert result == {}


@pytest.mark.asyncio
async def test_reflect_all_operators_equivalent_to_serial_reference() -> None:
    """并发路径输出与串行参考路径逐 operator 等价（spec 硬约束：单 operator 输出不变）。

    用相同 mock _reflect + 相同后处理逻辑，分别跑并发 _reflect_all_operators 与
    串行 for-loop 参考，断言 patches_by_operator 完全相等。若并发路径引入跨
    operator 写污染或结果重排，分组后会与串行参考发散。
    """
    operators = ["op1", "op2", "op3", "op4"]
    opt = _make_optimizer(operators, parallelism=2)
    valid_op_ids = set(operators)

    # 确定、逐 operator 可区分的 reflect 输出：每个 op 返回带自身 operator_id 的 patch
    async def _reflect(**kwargs: Any) -> list[RawPatch]:
        op_id = kwargs["operator_id"]
        return [_patch(op_id), _patch(op_id)]

    opt._reflect = _reflect  # type: ignore[method-assign]
    opt._format_batch = MagicMock(return_value="fmt")  # type: ignore[method-assign]
    opt._validate_raw_patch_operator_id = MagicMock(side_effect=lambda p, s: p)  # type: ignore[method-assign]

    attributed = {
        op: AttributedBatch(operator_id=op, failures=[], successes=[]) for op in operators
    }

    # 并发路径
    concurrent_result = await opt._reflect_all_operators(attributed, step=0, accumulation=0)

    # 串行参考路径：镜像 _reflect_all_operators 的 _reflect_one + 分组后处理
    serial_results: list[tuple[str, list[RawPatch]]] = []
    for op_id in operators:
        raw_patches = await opt._reflect(
            formatted_batch="fmt",
            skill_content=opt._current_skill_by_operator.get(op_id, ""),
            score_threshold=opt._score_threshold,
            batch_data=None,
            operator_id=op_id,
        )
        valid_patches = opt._validate_raw_patch_operator_id(raw_patches, valid_op_ids)
        serial_results.append((op_id, valid_patches))
    serial_result: dict[str, list[RawPatch]] = {op: [] for op in operators}
    for _op_id, valid_patches in serial_results:
        for raw_patch in valid_patches:
            serial_result[raw_patch.operator_id].append(raw_patch)

    assert concurrent_result == serial_result
    # 每个 operator 恰好 2 条自身 patch，无跨 operator 污染
    for op in operators:
        assert len(concurrent_result[op]) == 2
        assert all(rp.operator_id == op for rp in concurrent_result[op])
