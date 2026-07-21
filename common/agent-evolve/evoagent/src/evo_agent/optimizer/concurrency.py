# Copyright (c) Huawei Technologies Co., Ltd. 2026. All rights reserved.
"""Concurrency helpers for evolution pipelines.

Two sanctioned patterns bound concurrency without double acquisition:

1. ``gather_with_semaphore`` — for work whose coroutines do **not** themselves
   acquire the supplied semaphore (e.g. skill-content fetch in ``_build_operators``,
   C5 / #25). The factory is invoked lazily inside the semaphore slot.
2. Naked ``asyncio.gather`` — for LLM work, because every gathered coroutine enters
   the run-scoped ``LLMInvocation`` internally. The
   outer gather must **not** acquire the semaphore again, or a double-acquire
   deadlock results. Used by C2 / #3, C3 / #7, C4 / #19 (cross-operator
   reflect / aggregate / select). Each such site documents the inner-semaphore
   invariant in its docstring.

LLM concurrency is owned exclusively by ``LLMInvocation``; this helper remains
for non-LLM work such as skill-content fetches.
"""

from __future__ import annotations

import asyncio
from collections.abc import Awaitable, Callable


async def gather_with_semaphore[T](
    semaphore: asyncio.Semaphore,
    factories: list[Callable[[], Awaitable[T]]],
    *,
    return_exceptions: bool = False,
) -> list[T | BaseException]:
    """Run ``factories`` concurrently, each acquiring ``semaphore`` first.

    Each factory is called lazily inside the semaphore slot so we never
    construct (and thus never start) more coroutines than the semaphore
    allows at once. Use this for work that does not acquire ``semaphore``
    internally; for work that already acquires it (e.g. LLM calls via
    ``invoke_text_with_retry``), a naked ``asyncio.gather`` is the sanctioned
    alternative — see the module docstring.
    """

    async def _run(factory: Callable[[], Awaitable[T]]) -> T | BaseException:
        async with semaphore:
            coro = factory()
            try:
                return await coro
            except BaseException as exc:  # noqa: BLE001 — surface to caller
                if return_exceptions:
                    return exc
                raise

    return list(
        await asyncio.gather(*[_run(f) for f in factories], return_exceptions=return_exceptions)
    )
