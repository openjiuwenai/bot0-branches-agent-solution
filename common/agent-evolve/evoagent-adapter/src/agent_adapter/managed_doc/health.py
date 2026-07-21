"""Async E3 health probe for RestartApply (T8).

Two-phase state machine (spec §6.4):

* Phase 1 — down detection (``down_timeout``, ``poll_interval``): poll until one
  down sample (connection refused / timeout / non-200) is seen → ``down_seen=True``
  → phase 2. If ``down_timeout`` elapses with all-200 → ``down_seen=False`` →
  phase 2 (warn: no restart window observed).
* Phase 2 — up stable (``up_timeout``, ``poll_interval``): poll until
  ``up_consecutive`` consecutive 200s → success. A down sample resets the count.
  ``up_timeout`` elapsing without reaching the threshold → failure.

Returns ``(ok, down_seen)`` for RestartApply to record on the task.

Time and sleep are injected so the state machine is deterministic without real
wall-clock waits; production passes ``time.monotonic`` / ``asyncio.sleep``.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Awaitable, Callable

import httpx

_DOWN = 0  # synthesized status for connection failure / non-200


class HealthWatcher:
    """Polls ``health_url`` per E3; returns ``(ok, down_seen)``."""

    def __init__(
        self,
        *,
        client: httpx.AsyncClient,
        health_url: str,
        down_timeout: float,
        up_timeout: float,
        up_consecutive: int,
        poll_interval: float,
        now: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], Awaitable[None]] = asyncio.sleep,
    ) -> None:
        self._client = client
        self._health_url = health_url
        self._down_timeout = down_timeout
        self._up_timeout = up_timeout
        self._up_consecutive = up_consecutive
        self._poll_interval = poll_interval
        self._now = now
        self._sleep = sleep

    async def watch(self) -> tuple[bool, bool]:
        down_seen = await self._phase1_down()
        ok = await self._phase2_up()
        return ok, down_seen

    # ── phase 1: detect one down sample, or time out with down_seen=False ─

    async def _phase1_down(self) -> bool:
        start = self._now()
        while True:
            status = await self._probe()
            if self._is_down(status):
                return True
            if self._now() - start >= self._down_timeout:
                # 未观察到重启窗口（restart_cmd 可能 no-op）→ 降级，down_seen=False
                return False
            await self._sleep(self._poll_interval)

    # ── phase 2: require N consecutive ups ────────────────────────────

    async def _phase2_up(self) -> bool:
        start = self._now()
        consecutive = 0
        while True:
            status = await self._probe()
            if not self._is_down(status):
                consecutive += 1
                if consecutive >= self._up_consecutive:
                    return True
            else:
                consecutive = 0
            if self._now() - start >= self._up_timeout:
                return False
            await self._sleep(self._poll_interval)

    async def _probe(self) -> int:
        """Return the HTTP status, or ``_DOWN`` (0) on connection failure."""
        try:
            resp = await self._client.get(self._health_url, timeout=self._poll_interval)
            return resp.status_code
        except Exception:
            return _DOWN

    @staticmethod
    def _is_down(status: int) -> bool:
        return status != 200
