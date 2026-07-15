"""C0: gather_with_semaphore — 并发原语单元测试。"""

from __future__ import annotations

import asyncio

import pytest

from evo_agent.optimizer.concurrency import gather_with_semaphore


async def _val(i: int) -> int:
    await asyncio.sleep(0)
    return i


@pytest.mark.asyncio
async def test_runs_all_and_preserves_order() -> None:
    sem = asyncio.Semaphore(4)
    results = await gather_with_semaphore(sem, [lambda i=i: _val(i) for i in range(5)])
    assert results == [0, 1, 2, 3, 4]


@pytest.mark.asyncio
async def test_bounds_concurrency_to_semaphore() -> None:
    """同时在跑的 coroutine 数不超过 semaphore 容量。"""
    sem = asyncio.Semaphore(2)
    active = 0
    peak = 0
    lock = asyncio.Lock()

    async def _track() -> None:
        nonlocal active, peak
        async with lock:
            active += 1
            peak = max(peak, active)
        await asyncio.sleep(0.01)
        async with lock:
            active -= 1

    await gather_with_semaphore(sem, [lambda: _track() for _ in range(10)])
    assert peak <= 2


@pytest.mark.asyncio
async def test_return_exceptions_surfaces_errors() -> None:
    sem = asyncio.Semaphore(4)

    async def _boom() -> int:
        raise RuntimeError("boom")

    results = await gather_with_semaphore(
        sem,
        [lambda: _val(1), lambda: _boom(), lambda: _val(3)],
        return_exceptions=True,
    )
    assert results[0] == 1
    assert isinstance(results[1], RuntimeError)
    assert results[2] == 3


@pytest.mark.asyncio
async def test_propagates_when_not_returning_exceptions() -> None:
    sem = asyncio.Semaphore(4)

    async def _boom() -> int:
        raise RuntimeError("boom")

    with pytest.raises(RuntimeError):
        await gather_with_semaphore(sem, [lambda: _val(1), lambda: _boom()])


@pytest.mark.asyncio
async def test_empty_factories_returns_empty() -> None:
    sem = asyncio.Semaphore(4)
    assert await gather_with_semaphore(sem, []) == []
