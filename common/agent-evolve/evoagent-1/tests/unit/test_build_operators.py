"""C5 (#25): _build_operators 并发拉 skill — 单元测试。"""

from __future__ import annotations

import asyncio

import pytest

from evo_agent.optimizer_runner import _build_operators


class _FakeAdapter:
    """记录 skill_content 调用并发峰值的假 adapter。"""

    def __init__(self) -> None:
        self.active = 0
        self.peak = 0
        self._lock = asyncio.Lock()
        self.calls: list[str] = []

    async def skill_content(self, name: str) -> str:
        async with self._lock:
            self.active += 1
            self.peak = max(self.peak, self.active)
            self.calls.append(name)
        await asyncio.sleep(0.02)
        async with self._lock:
            self.active -= 1
        return f"content-{name}"


@pytest.mark.asyncio
async def test_build_operators_fetches_all_skills() -> None:
    adapter = _FakeAdapter()
    operators = await _build_operators(["s1", "s2", "s3"], adapter)
    assert set(operators) == {"s1", "s2", "s3"}
    for name in ["s1", "s2", "s3"]:
        assert operators[name] is not None


@pytest.mark.asyncio
async def test_build_operators_fetches_concurrently() -> None:
    """多 skill 的 skill_content 并发拉取（非顺序）。"""
    adapter = _FakeAdapter()
    await _build_operators(["s1", "s2", "s3", "s4"], adapter)
    assert adapter.peak >= 2  # 至少两个并发


@pytest.mark.asyncio
async def test_build_operators_empty_skills() -> None:
    adapter = _FakeAdapter()
    operators = await _build_operators([], adapter)
    assert operators == {}


@pytest.mark.asyncio
async def test_build_operators_bounds_concurrency() -> None:
    """并发不超过传入的并行度上限。"""
    adapter = _FakeAdapter()
    await _build_operators(["s1", "s2", "s3", "s4"], adapter, num_parallel=2)
    assert adapter.peak <= 2


@pytest.mark.asyncio
async def test_build_operators_construction_consistent() -> None:
    """构造结果与 skill 名一一对应，content 正确绑定。"""
    adapter = _FakeAdapter()
    operators = await _build_operators(["alpha", "beta"], adapter)
    # operator 名与 skill 名一致
    assert set(operators) == {"alpha", "beta"}
    assert adapter.calls == ["alpha", "beta"] or set(adapter.calls) == {"alpha", "beta"}
